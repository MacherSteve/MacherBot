package de.pvptrainer.kits;

import com.google.gson.JsonObject;
import de.pvptrainer.TrainerClient;
import de.pvptrainer.arena.ArenaRecoverySave;
import de.pvptrainer.arena.ArenaRound;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.session.TrainingSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TrainingKit {
    private static final Logger LOG = LoggerFactory.getLogger("pvptrainer-kits");
    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> recover(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ACTIVE.remove(handler.player.getUUID()));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> landed(source));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> landed(source));
    }

    private static void landed(DamageSource source) {
        if (source.getDirectEntity() instanceof ServerPlayer player && !player.getMainHandItem().isEmpty()) {
            used(player);
        }
    }

    public static String startingFingerprint(ServerPlayer player) {
        Active active = ACTIVE.get(player.getUUID());
        if (active == null) {
            throw new IllegalStateException("Spieler-Kit fehlt.");
        }
        return KitLibrary.fingerprint(player.registryAccess(), active.kit);
    }

    public static KitLoadout botLoadout(ServerPlayer player, TrainingSettings.Mode mode) {
        return KitLibrary.selected(player.registryAccess(), mode, true);
    }

    private static Path journal(ServerPlayer player) {
        return player.level().getServer().getWorldPath(LevelResource.ROOT)
            .resolve("pvptrainer/inventory")
            .resolve(player.getUUID() + ".json");
    }

    public static void recover(ServerPlayer player) {
        if (!TrainingArena.hasReturnPoint(player) && Files.isRegularFile(journal(player)) && !active(player)) {
            try {
                end(player);
            } catch (RuntimeException error) {
                LOG.error("Wiederherstellung des Trainingsinventars fehlgeschlagen", error);
                player.sendSystemMessage(Component.literal("Trainingsinventar konnte nicht wiederhergestellt werden. Die Sicherung bleibt erhalten."));
            }
        }
    }

    public static void begin(ServerPlayer player, TrainingSettings.Mode mode) {
        begin(player, mode, KitLibrary.personalized(player.registryAccess(), mode, KitLibrary.selected(player.registryAccess(), mode, false).id()));
    }

    public static void begin(ServerPlayer player, TrainingSettings.Mode mode, KitLoadout loadout) {
        if (player.containerMenu != player.inventoryMenu) {
            throw new IllegalStateException("Schließe zuerst den geöffneten Behälter.");
        }

        List<ItemStack> slots = loadout.slots();
        MacherItems.namePlayerKit(slots);
        for (ItemStack stack : slots) {
            if (stack.is(Items.END_CRYSTAL)) {
                CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean("pvptrainer:training_crystal", true));
            }
        }

        Path journalFile = journal(player);
        if (!active(player)) {
            if (Files.isRegularFile(journalFile)) {
                end(player);
            }
            if (Files.exists(journalFile)) {
                throw new IllegalStateException("Die Inventarsicherung konnte noch nicht abgeschlossen werden.");
            }
            KitLibrary.write(journalFile, backup(player));
        }

        Active active = new Active(player.level().getServer(), mode, loadout, slots);
        ACTIVE.put(player.getUUID(), active);
        clearTemporarySlots(player);
        player.getInventory().clearContent();

        for (int slot = 0; slot < slots.size(); slot++) {
            player.getInventory().setItem(slot, slots.get(slot).copy());
        }

        int selectedSlot = 0;
        if (mode == TrainingSettings.Mode.CRYSTAL && player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            for (int hotbarSlot = 0; hotbarSlot < 9; hotbarSlot++) {
                if (player.getInventory().getItem(hotbarSlot).is(Items.TOTEM_OF_UNDYING)) {
                    selectedSlot = hotbarSlot;
                    break;
                }
            }
        }

        player.getInventory().setSelectedSlot(selectedSlot);
        player.removeAllEffects();
        loadout.effects().forEach(player::addEffect);
        sync(player);
    }

    public static void nameNewItems(ServerPlayer player) {
        if (!active(player) || !TrainingArena.isArena(player.level())) {
            return;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && !stack.has(DataComponents.CUSTOM_NAME)) {
                MacherItems.name(stack);
            }
        }
    }

    private static JsonObject backup(ServerPlayer player) {
        RegistryAccess registries = player.registryAccess();
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            items.add(player.getInventory().getItem(slot).copy());
        }

        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("owner", player.getUUID().toString());
        json.addProperty("selected", player.getInventory().getSelectedSlot());
        json.add("inventory", KitLibrary.encode(registries, ItemStack.OPTIONAL_CODEC.listOf(), items));
        json.add("effects", KitLibrary.encode(registries, MobEffectInstance.CODEC.listOf(), List.copyOf(player.getActiveEffects())));
        json.add("cursor", KitLibrary.encode(registries, ItemStack.OPTIONAL_CODEC, player.inventoryMenu.getCarried()));
        json.add("craft", KitLibrary.encode(registries, ItemStack.OPTIONAL_CODEC.listOf(), player.inventoryMenu.getCraftSlots().getItems()));
        return json;
    }

    public static SavedInventory readBackup(ServerPlayer player) {
        Path journalFile = journal(player);
        if (!Files.isRegularFile(journalFile)) {
            if (active(player)) {
                throw new IllegalStateException("Sicherung des Weltinventars fehlt.");
            }
            return null;
        }

        JsonObject json = KitLibrary.read(journalFile);
        if (!player.getUUID().toString().equals(json.get("owner").getAsString()) || json.get("schema").getAsInt() != 1) {
            throw new IllegalStateException("Ungültige Inventarsicherung.");
        }

        RegistryAccess registries = player.registryAccess();
        List<ItemStack> items = KitLibrary.decode(registries, ItemStack.OPTIONAL_CODEC.listOf(), json.get("inventory"));
        List<MobEffectInstance> effects = KitLibrary.decode(registries, MobEffectInstance.CODEC.listOf(), json.get("effects"));
        List<ItemStack> craft = KitLibrary.decode(registries, ItemStack.OPTIONAL_CODEC.listOf(), json.get("craft"));
        ItemStack cursor = KitLibrary.decode(registries, ItemStack.OPTIONAL_CODEC, json.get("cursor"));
        Inventory inventory = player.getInventory();
        int selectedSlot = json.get("selected").getAsInt();
        if (items.size() == inventory.getContainerSize()
            && craft.size() == player.inventoryMenu.getCraftSlots().getContainerSize()
            && selectedSlot >= 0
            && selectedSlot < 9) {
            return new SavedInventory(items, selectedSlot, craft, cursor, effects);
        }
        throw new IllegalStateException("Unvollständige Inventarsicherung.");
    }

    public static void completeRestore(ServerPlayer player) throws IOException {
        ACTIVE.remove(player.getUUID());
        Files.deleteIfExists(journal(player));
    }

    static void end(ServerPlayer player) {
        SavedInventory savedInventory = readBackup(player);
        if (savedInventory == null) {
            return;
        }

        savedInventory.restore(player);
        try {
            if (!ArenaRecoverySave.persist(player.level().getServer(), player)) {
                throw new IOException("Spielstand nicht geschrieben.");
            }
            completeRestore(player);
        } catch (IOException error) {
            throw new IllegalStateException("Das Weltinventar wurde wiederhergestellt, die Sicherung bleibt erhalten.", error);
        }
    }

    private static void sync(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastFullState();
        player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
    }

    private static void clearTemporarySlots(ServerPlayer player) {
        player.stopUsingItem();
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.getCraftSlots().clearContent();
    }

    public static boolean active(ServerPlayer player) {
        Active active = ACTIVE.get(player.getUUID());
        return active != null && active.server == player.level().getServer();
    }

    public static boolean layoutLocked(ServerPlayer player) {
        Active active = ACTIVE.get(player.getUUID());
        return active != null && active.locked;
    }

    public static void used(ServerPlayer player) {
        Active active = ACTIVE.get(player.getUUID());
        if (active != null && active.server == player.level().getServer()) {
            active.locked = true;
        }
    }

    public static int usableTotems(ItemStack stack) {
        return stack.is(Items.TOTEM_OF_UNDYING) && stack.has(DataComponents.DEATH_PROTECTION) ? stack.getCount() : 0;
    }

    public static int refillSupply(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        int total = usableTotems(player.containerMenu.getCarried());

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            total += usableTotems(inventory.getItem(slot));
        }
        for (ItemStack craftStack : player.inventoryMenu.getCraftSlots().getItems()) {
            total += usableTotems(craftStack);
        }
        return total;
    }

    public static void popped(ServerPlayer player, ItemStack stack) {
        used(player);
        TrainingSession session = TrainerClient.session(player.level().getServer());
        if (session != null) {
            session.playerPopped(player, stack);
        }
    }

    public static void sorted(ServerPlayer player) {
        Active active = ACTIVE.get(player.getUUID());
        if (active == null
            || active.locked
            || player.containerMenu != player.inventoryMenu
            || !player.inventoryMenu.getCarried().isEmpty()
            || !player.inventoryMenu.getCraftSlots().isEmpty()) {
            return;
        }

        List<ItemStack> projected = projectLayout(active.runtime, active.kit.slots(), ArenaRound.layoutForSorting(player, active.runtime), true);
        if (projected == null) {
            return;
        }

        try {
            if (KitLibrary.remember(player.registryAccess(), active.mode, active.kit, projected)) {
                TrainingSession session = TrainerClient.session(active.server);
                if (session != null) {
                    KitLoadout updated = new KitLoadout(active.kit.id(), active.kit.name(), active.kit.source(), projected, active.kit.effects());
                    session.layoutUpdated(player, updated);
                }
            }
        } catch (RuntimeException error) {
            LOG.error("Trainingslayout konnte nicht gespeichert werden", error);
            player.sendSystemMessage(Component.literal("Die Kit-Sortierung konnte nicht gespeichert werden."));
        }
    }

    static boolean sameContents(List<ItemStack> expected, List<ItemStack> actual) {
        return projectLayout(expected, expected, actual, false) != null;
    }

    private static List<ItemStack> projectLayout(
        List<ItemStack> runtimeSlots,
        List<ItemStack> templateSlots,
        List<ItemStack> candidateSlots,
        boolean allowWear
    ) {
        if (candidateSlots.size() != runtimeSlots.size()) {
            return null;
        }

        int[] remaining = runtimeSlots.stream().mapToInt(ItemStack::getCount).toArray();
        List<ItemStack> projected = KitLibrary.emptySlots();

        for (int targetSlot = 0; targetSlot < candidateSlots.size(); targetSlot++) {
            ItemStack candidate = candidateSlots.get(targetSlot);
            if (candidate.isEmpty()) {
                continue;
            }

            int needed = candidate.getCount();
            for (int sourceSlot = 0; sourceSlot < runtimeSlots.size() && needed > 0; sourceSlot++) {
                if (remaining[sourceSlot] != 0 && matches(runtimeSlots.get(sourceSlot), candidate, allowWear)) {
                    int taken = Math.min(remaining[sourceSlot], needed);
                    if (projected.get(targetSlot).isEmpty()) {
                        projected.set(targetSlot, templateSlots.get(sourceSlot).copyWithCount(candidate.getCount()));
                    } else if (!ItemStack.isSameItemSameComponents(projected.get(targetSlot), templateSlots.get(sourceSlot))) {
                        return null;
                    }
                    needed -= taken;
                    remaining[sourceSlot] -= taken;
                }
            }
            if (needed > 0) {
                return null;
            }
        }

        return Arrays.stream(remaining).anyMatch(count -> count != 0) ? null : projected;
    }

    private static boolean matches(ItemStack expected, ItemStack actual, boolean allowWear) {
        if (ItemStack.isSameItemSameComponents(expected, actual)) {
            return true;
        }
        if (allowWear && actual.isDamageableItem() && actual.is(expected.getItem()) && actual.getDamageValue() >= expected.getDamageValue()) {
            ItemStack normalized = actual.copy();
            normalized.setDamageValue(expected.getDamageValue());
            return ItemStack.isSameItemSameComponents(expected, normalized);
        }
        return false;
    }

    private static final class Active {
        final MinecraftServer server;
        final KitLoadout kit;
        final TrainingSettings.Mode mode;
        final List<ItemStack> runtime;
        boolean locked;

        Active(MinecraftServer server, TrainingSettings.Mode mode, KitLoadout kit, List<ItemStack> runtime) {
            this.server = server;
            this.mode = mode;
            this.kit = kit;
            this.runtime = runtime;
        }
    }

    public record SavedInventory(List<ItemStack> items, int selected, List<ItemStack> craft, ItemStack cursor, List<MobEffectInstance> effects) {
        public void restore(ServerPlayer player) {
            if (player.containerMenu != player.inventoryMenu) {
                player.closeContainer();
            }

            player.stopUsingItem();
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < this.items.size(); slot++) {
                inventory.setItem(slot, this.items.get(slot));
            }
            inventory.setSelectedSlot(this.selected);

            for (int slot = 0; slot < this.craft.size(); slot++) {
                player.inventoryMenu.getCraftSlots().setItem(slot, this.craft.get(slot));
            }

            player.inventoryMenu.setCarried(this.cursor);
            player.removeAllEffects();
            this.effects.forEach(player::addEffect);
            TrainingKit.sync(player);
        }
    }
}
