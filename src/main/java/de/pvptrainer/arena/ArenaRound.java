package de.pvptrainer.arena;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.KitLibrary;
import de.pvptrainer.TrainerClient;
import de.pvptrainer.kits.TrainingKit;
import de.pvptrainer.session.TrainingSession;
import de.pvptrainer.kits.TrainingSettings;

import com.mojang.logging.LogUtils;
import com.google.gson.JsonParser;
import de.pvptrainer.mixin.AttackStrengthAccess;
import de.pvptrainer.mixin.GravityAccess;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.syncher.SynchedEntityData.DataValue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public final class ArenaRound {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<UUID,Integer> recovering = new HashMap<>();

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ArenaRound::onTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> recovering.clear());
    }

    static boolean joined(ServerPlayer player) {
        recovering.put(player.getUUID(), player.level().getServer().getTickCount()+2);
        return true;
    }

    private static void onTick(MinecraftServer server) {
        if (recovering.isEmpty()) return;
        Iterator<Map.Entry<UUID,Integer>> iterator = recovering.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry=iterator.next();
            if (server.getTickCount() < entry.getValue()) continue;
            ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());
            if (player==null || !player.connection.isAcceptingMessages()) { iterator.remove(); continue; }
            if (!player.connection.hasClientLoaded()) continue;
            TrainingSession session=TrainerClient.session(server);
            if (session!=null && session.status().active()) { iterator.remove(); continue; }
            if (TrainingArena.recoverPending(player)) iterator.remove();
            else entry.setValue(server.getTickCount()+100);
        }
    }

    static boolean missingReturn(ServerPlayer player) {
        if (!TrainingArena.isArena(player.level())) return true;
        // Some old saves may already have lost the return record. Retain the inventory
        // and use the world spawn instead of trapping the player in a training dimension.
        MinecraftServer server=player.level().getServer();
        ServerLevel target=server.findRespawnDimension();
        if (TrainingArena.isArena(target)) target=server.overworld();
        BlockPos spawn=player.adjustSpawnLocation(target, target.getRespawnData().pos());
        Vec3 pos=Vec3.atBottomCenterOf(spawn);
        if (!player.teleportTo(target,pos.x,pos.y,pos.z,Set.of(),player.getYRot(),player.getXRot(),true)) return false;
        player=TrainingArena.currentPlayer(player);
        if (player.level()!=target) return false;
        player.setNoGravity(false);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        syncReturnMotion(player);
        TrainingKit.recover(player);
        player.sendSystemMessage(Component.literal("Rückkehrpunkt fehlt. Du bist am Weltspawn."));
        return true;
    }

    static void syncReturnMotion(ServerPlayer player) {
        // A dimension respawn copies the old client's non-default entity data and velocity.
        // A server value already reset to its default does not generate a new dirty update.
        var gravity = DataValue.create(GravityAccess.pvptrainer$noGravity(), player.isNoGravity());
        player.connection.send(new ClientboundSetEntityDataPacket(player.getId(), List.of(gravity)));
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
    }

    public static void restartSaved(TrainingSession session, MinecraftServer server, ServerPlayer player) {
        TrainingSettings settings=TrainerClient.selected();
        session.start(server, player.getUUID(), settings, TrainerClient.options(settings.mode()));
    }

    public static void stopIdle(MinecraftServer server) {
        var profile=server.getSingleplayerProfile();
        if (profile==null) return;
        ServerPlayer player=server.getPlayerList().getPlayer(profile.id());
        if (player!=null && (TrainingArena.hasReturnPoint(player)||TrainingArena.isArena(player.level()))) {
            TrainingArena.recoverPending(player);
        }
    }

    static boolean airStart(boolean original, DrillOptions options) {
        return original || options.drill() == Drill.ELYTRA_SMASH
            || (options.drill() == Drill.BACKSTAB || options.drill().centeredMaceDrill()) && options.startHeight() > 0;
    }
    static int elevation(DrillOptions options) {
        return options.playerElevation();
    }

    // The forge is symmetric about the centre of block (0, 0). Preserve the approach vector by
    // translating both participants, including the player's platform, by the same horizontal offset.
    private static final double CENTER = 0.5;

    static DrillOptions layoutOptions(DrillOptions options, TrainingSettings settings) {
        return settings.maceCenterLocked() ? DrillOptions.forDrill(Drill.PASSIVE) : options;
    }

    static boolean enterRound(ServerPlayer player, ServerLevel arena, double x, double y, double z,
            Set<Relative> relative, float yaw, float pitch, boolean resetCamera, Vec3 target, TrainingSettings settings) {
        if (settings.maceCenterLocked()) {
            x += CENTER - target.x;
            z += CENTER - target.z;
        }
        return player.teleportTo(arena, x, y, z, relative, yaw, pitch, resetCamera);
    }

    static Vec3 stageScenario(Vec3 target, ServerPlayer player, DrillOptions options, TrainingSettings settings) {
        if (options.drill() == Drill.ELYTRA_SMASH) equipElytra(player);
        if (settings.maceCenterLocked()) {
            Vec3 center = new Vec3(CENTER, target.y, CENTER);
            if (options.drill() == Drill.LOW_WEB_SMASH) {
                player.level().setBlockAndUpdate(BlockPos.containing(center).above(), Blocks.COBWEB.defaultBlockState());
            }
            if (options.startHeight() > 0 && !TrainingArena.airStart(options)) {
                BlockPos floor = player.blockPosition().below();
                for (int x = -1; x <= 1; x++)
                    for (int z = -2; z <= 0; z++)
                        player.level().setBlockAndUpdate(floor.offset(x, 0, z), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            }
            if (options.preparedBase()) {
                player.level().setBlockAndUpdate(BlockPos.containing(CENTER, ForgeArena.FLOOR, CENTER).east(), Blocks.OBSIDIAN.defaultBlockState());
            }
            return center;
        }
        if (options.drill().needsCornerWalls()) {
            double dx = target.x - player.getX(), dz = target.z - player.getZ();
            Direction back = Math.abs(dx) > Math.abs(dz)
                ? (dx < 0 ? Direction.WEST : Direction.EAST)
                : (dz < 0 ? Direction.NORTH : Direction.SOUTH);
            Direction side = back.getClockWise();
            BlockPos feet = BlockPos.containing(target);
            BlockPos backWall = feet.relative(back);
            BlockPos sideWall = feet.relative(side);
            // Build two perpendicular Forge walls meeting cleanly at (back + side) to form a 90° corner.
            for (int along = -4; along <= 1; along++) {
                for (int height = 0; height < 5; height++) {
                    var block = cornerWallBlock(along, height);
                    player.level().setBlockAndUpdate(backWall.relative(side, along).above(height), block.defaultBlockState());
                }
            }
            for (int depth = -4; depth <= 0; depth++) {
                for (int height = 0; height < 5; height++) {
                    var block = cornerWallBlock(depth, height);
                    player.level().setBlockAndUpdate(sideWall.relative(back, depth).above(height), block.defaultBlockState());
                }
            }
            if (options.drill() == Drill.CORNER_WEB_DTAP && options.preparedBase()) {
                player.level().setBlockAndUpdate(feet.below().relative(back.getOpposite()), Blocks.OBSIDIAN.defaultBlockState());
                player.level().setBlockAndUpdate(feet.below().relative(side.getOpposite()), Blocks.OBSIDIAN.defaultBlockState());
            }
            return Vec3.atBottomCenterOf(feet).add(
                back.getStepX() * 0.14 + side.getStepX() * 0.14,
                0,
                back.getStepZ() * 0.14 + side.getStepZ() * 0.14
            );
        }
        if (options.drill() != Drill.HEAD_WEB && options.drill() != Drill.OBI_TRAP) return target;

        // Use the wall face behind the bot even when the approach angle or distance was customized.
        double dx = target.x - player.getX(), dz = target.z - player.getZ();
        Direction back = Math.abs(dx) > Math.abs(dz)
            ? (dx < 0 ? Direction.WEST : Direction.EAST)
            : (dz < 0 ? Direction.NORTH : Direction.SOUTH);
        Direction across = back.getClockWise();
        BlockPos feet = BlockPos.containing(target);
        BlockPos wall = feet.relative(back);
        for (int lateral = -4; lateral <= 4; lateral++) {
            int width = Math.abs(lateral);
            for (int height = 0; height < 5; height++) {
                // Broad flat face for bucket/web clicks, with the same plinth, band and cap as the forge.
                var block = Blocks.POLISHED_BLACKSTONE_BRICKS;
                if (height == 0 || height == 4) block = Blocks.POLISHED_BLACKSTONE;
                else if (height == 3) block = width == 4 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.RED_NETHER_BRICKS;
                else if (width == 4) block = Blocks.POLISHED_BASALT;
                else if ((lateral == -2 && height == 2) || (lateral == 2 && height == 1)) block = Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS;
                player.level().setBlockAndUpdate(wall.relative(across, lateral).above(height), block.defaultBlockState());
            }
        }
        return Vec3.atBottomCenterOf(feet).add(back.getStepX() * 0.18, 0, back.getStepZ() * 0.18);
    }

    private static net.minecraft.world.level.block.Block cornerWallBlock(int span, int height) {
        if (height == 0 || height == 4) return Blocks.POLISHED_BLACKSTONE;
        if (height == 3) return Math.abs(span) == 4 || span == 1 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.RED_NETHER_BRICKS;
        if (Math.abs(span) == 4 || span == 1) return Blocks.POLISHED_BASALT;
        if ((span == -2 && height == 2) || (span == -1 && height == 1)) return Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS;
        return Blocks.POLISHED_BLACKSTONE_BRICKS;
    }

    private static void equipElytra(ServerPlayer player) {
        var inventory = player.getInventory();
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(Items.ELYTRA)) return;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack wings = inventory.getItem(slot);
            if (!wings.is(Items.ELYTRA)) continue;
            inventory.setItem(slot, chest);
            player.setItemSlot(EquipmentSlot.CHEST, wings);
            return;
        }
        if (player.getOffhandItem().is(Items.ELYTRA)) {
            ItemStack wings = player.getOffhandItem();
            player.setItemSlot(EquipmentSlot.OFFHAND, chest);
            player.setItemSlot(EquipmentSlot.CHEST, wings);
            return;
        }
        // A custom kit can omit the wings. This equipment is scoped to the training round.
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        if (!chest.isEmpty()) inventory.placeItemBackInInventory(chest);
    }

    /** The permanent kit layout is independent of the equipment swap for this drill. */
    public static List<ItemStack> layoutForSorting(net.minecraft.world.entity.player.Player player, List<ItemStack> original) {
        List<ItemStack> sorted = KitLibrary.inventory(player);
        if (!(player instanceof ServerPlayer owner) || !TrainingArena.isArena(owner.level())) return sorted;
        TrainingSession session = TrainerClient.session(owner.level().getServer());
        if (session == null || session.status().drill() != Drill.ELYTRA_SMASH) return sorted;
        final int chestSlot = 38;
        ItemStack startingChest = original.get(chestSlot), wings = sorted.get(chestSlot);
        if (startingChest.is(Items.ELYTRA) || !wings.is(Items.ELYTRA)) return sorted;
        int source = -1;
        for (int i = 0; i < original.size(); i++) {
            if ((i < 36 || i == 40) && original.get(i).is(Items.ELYTRA)) { source = i; break; }
        }
        int stowed = source >= 0 && ItemStack.isSameItemSameComponents(sorted.get(source), startingChest) ? source : -1;
        if (stowed < 0) {
            for (int i = 0; i < sorted.size(); i++) {
                if ((i < 36 || i == 40) && ItemStack.isSameItemSameComponents(sorted.get(i), startingChest)) { stowed = i; break; }
            }
        }
        if (stowed >= 0) {
            sorted.set(chestSlot, sorted.get(stowed));
            sorted.set(stowed, source < 0 ? ItemStack.EMPTY : wings);
        } else if (source < 0 && startingChest.isEmpty()) {
            sorted.set(chestSlot, ItemStack.EMPTY);
        }
        return sorted;
    }

    private static Path slotsFile(ServerPlayer player) {
        return FabricLoader.getInstance().getConfigDir().resolve("pvptrainer").resolve("start-slots-"+player.getUUID()+".properties");
    }

    private static Properties readSlots(Path path) throws IOException {
        Properties saved=new Properties();
        if (Files.isRegularFile(path)) try (Reader reader=Files.newBufferedReader(path)) { saved.load(reader); }
        return saved;
    }

    public static void staged(ServerPlayer player, LivingEntity bot, TrainingSettings settings) {
        TrainingTarget.select(bot);
        clearDroppedItems(bot);
        player.lookAt(Anchor.EYES,bot.getEyePosition());
        try {
            Properties saved=readSlots(slotsFile(player));
            String mode=settings.mode().name();
            String value=saved.getProperty(mode);
            if (value==null) return;
            int slot=Integer.parseInt(value);
            if (slot>=0 && slot<9) {
                slot=findStartSlot(player,saved,mode,slot);
                player.getInventory().setSelectedSlot(slot);
                player.connection.send(new ClientboundSetHeldSlotPacket(slot));
                ((AttackStrengthAccess) player).pvptrainer$setAttackStrengthTicker(200);
            }
        } catch (IOException|IllegalArgumentException e) { LOG.warn("Hotbar-Auswahl konnte nicht geladen werden",e); }
    }

    private static String itemAt(ServerPlayer player,int slot) {
        return BuiltInRegistries.ITEM.getKey(player.getInventory().getItem(slot).getItem()).toString();
    }

    // Keep enchantments and other identifying components when two tools share an item ID.
    private static ItemStack comparableStack(ServerPlayer player,int slot) {
        ItemStack copy=player.getInventory().getItem(slot).copyWithCount(1);
        if(copy.isDamageableItem()) copy.setDamageValue(0);
        return copy;
    }

    private static int findStartSlot(ServerPlayer player,Properties prefs,String mode,int fallback) {
        String encoded=prefs.getProperty(mode+".stack");
        if(encoded!=null) {
            try {
                ItemStack preferred=KitLibrary.decode(player.registryAccess(),ItemStack.OPTIONAL_CODEC,JsonParser.parseString(encoded));
                if(preferred.isEmpty() || ItemStack.isSameItemSameComponents(preferred,comparableStack(player,fallback))) return fallback;
                for(int i=0;i<9;i++) {
                    if(ItemStack.isSameItemSameComponents(preferred,comparableStack(player,i))) return i;
                }
            } catch(RuntimeException e) {
                LOG.warn("Gespeichertes Startitem konnte nicht gelesen werden",e);
            }
        }
        String item=prefs.getProperty(mode+".item");
        if(item!=null && !item.equals("minecraft:air") && !item.equals(itemAt(player,fallback))) {
            for(int i=0;i<9;i++) if(item.equals(itemAt(player,i))) return i;
        }
        return fallback;
    }

    public static void started(ServerPlayer player, LivingEntity bot, TrainingSettings settings) {
        clearDroppedItems(bot);
        String slot=Integer.toString(player.getInventory().getSelectedSlot());
        Path dest=slotsFile(player);
        try {
            Properties saved=readSlots(dest);
            String mode=settings.mode().name();
            String item=itemAt(player,player.getInventory().getSelectedSlot());
            String stack=KitLibrary.encode(player.registryAccess(),ItemStack.OPTIONAL_CODEC,
                comparableStack(player,player.getInventory().getSelectedSlot())).toString();
            String oldSlot=(String)saved.setProperty(mode, slot);
            String oldItem=(String)saved.setProperty(mode+".item", item);
            String oldStack=(String)saved.setProperty(mode+".stack",stack);
            if (slot.equals(oldSlot) && item.equals(oldItem) && stack.equals(oldStack)) return;
            Files.createDirectories(dest.getParent());
            Path tmp=Files.createTempFile(dest.getParent(),"start-slots-", ".tmp");
            try {
                try (Writer writer=Files.newBufferedWriter(tmp)) { saved.store(writer,null); }
                try { Files.move(tmp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(tmp,dest,StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(tmp); }
        } catch (IOException|RuntimeException e) { LOG.warn("Hotbar-Auswahl konnte nicht gespeichert werden",e); }
    }

    private static void clearDroppedItems(LivingEntity bot) {
        if (bot.level() instanceof ServerLevel level && TrainingArena.isArena(level)) {
            level.getEntities(EntityTypeTest.forClass(ItemEntity.class), item -> true).forEach(Entity::discard);
        }
    }

    public static void prompt(ServerPlayer player) {
        player.sendOverlayMessage(Component.literal("Bewegen, angreifen oder benutzen, um zu starten"));
    }
}
