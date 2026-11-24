package de.pvptrainer.arena;

import com.mojang.logging.LogUtils;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.KitLoadout;
import de.pvptrainer.kits.TrainingKit;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.mixin.AttackStrengthAccess;
import de.pvptrainer.mixin.CooldownAccess;
import de.pvptrainer.session.TrainingResources;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayer.RespawnConfig;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Abilities.Packed;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public final class TrainingArena {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("pvptrainer", "arena"));
    private static final int START_Z = -4;
    private static final int FLOOR = 64;
    private static boolean uhc;

    public static void register() {
        ArenaRound.register();
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
            level.dimension() == KEY && level.getBlockState(hitResult.getBlockPos()).is(Blocks.ENDER_CHEST)
                ? InteractionResult.FAIL
                : InteractionResult.PASS
        );
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ArenaRound.joined(handler.player));
    }

    public static boolean isArena(Level level) {
        return level.dimension() == KEY;
    }

    public static boolean isArena(ServerLevel level) {
        return isArena((Level) level);
    }

    public static boolean suppressRegeneration(ServerPlayer player) {
        return uhc && isArena(player.level());
    }

    /** Arena combat treats Peaceful as Normal so drills still deal damage; Easy/Normal/Hard match Vanilla. */
    public static Difficulty effectiveDamageDifficulty(Difficulty difficulty, boolean inArena) {
        return difficulty == Difficulty.PEACEFUL && inArena ? Difficulty.NORMAL : difficulty;
    }

    /** Resolves effective damage difficulty in the arena (default Hard, or world difficulty when configured). */
    public static Difficulty resolveDamageDifficulty(ServerLevel level) {
        return isArena(level) ? TrainingResources.arenaDifficulty(level) : level.getDifficulty();
    }

    /** Exact Vanilla Player.hurtServer() difficulty scaling for difficulty-scaled damage sources. */
    public static float scalePlayerDamage(float amount, Difficulty difficulty, boolean scalesWithDifficulty, boolean inArena) {
        if (!scalesWithDifficulty) {
            return amount;
        }
        Difficulty effective = effectiveDamageDifficulty(difficulty, inArena);
        return switch (effective) {
            case PEACEFUL -> 0.0F;
            case EASY -> Math.min(amount / 2.0F + 1.0F, amount);
            case NORMAL -> amount;
            case HARD -> amount * 3.0F / 2.0F;
        };
    }

    private static Path returnFile(ServerPlayer player) {
        return player.level().getServer().getWorldPath(LevelResource.ROOT)
            .resolve("pvptrainer")
            .resolve("return-" + player.getUUID() + ".dat");
    }

    public static boolean hasReturnPoint(ServerPlayer player) {
        return Files.exists(returnFile(player));
    }

    public static boolean recoverPending(ServerPlayer player) {
        if (!hasReturnPoint(player)) {
            return ArenaRound.missingReturn(player);
        }

        try {
            leave(player);
            player = currentPlayer(player);
            return !hasReturnPoint(player);
        } catch (RuntimeException error) {
            LOG.error("MacherBot: Rückkehrdaten blieben für die Wiederherstellung erhalten", error);
            player.sendSystemMessage(Component.literal(error.getMessage()));
            return false;
        }
    }

    private static CompoundTag capture(ServerPlayer player) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.getFoodData().addAdditionalSaveData(output);
        output.store("abilities", Packed.CODEC, player.getAbilities().pack());
        if (player.getRespawnConfig() != null) {
            output.store("returnSpawn", RespawnConfig.CODEC, player.getRespawnConfig());
        }

        CompoundTag tag = output.buildResult();
        CooldownAccess cooldownAccess = (CooldownAccess) player.getCooldowns();
        CompoundTag cooldownsTag = new CompoundTag();
        cooldownAccess.pvptrainer$groups().forEach((group, timer) -> {
            int remaining = ((CooldownAccess.Timer) timer).pvptrainer$end() - cooldownAccess.pvptrainer$clock();
            if (remaining > 0) {
                cooldownsTag.putInt(group.toString(), remaining);
            }
        });
        tag.put("cooldowns", cooldownsTag);
        tag.putBoolean("noGravity", player.isNoGravity());
        tag.putString("dimension", player.level().dimension().identifier().toString());
        tag.putDouble("x", player.getX());
        tag.putDouble("y", player.getY());
        tag.putDouble("z", player.getZ());
        tag.putFloat("yaw", player.getYRot());
        tag.putFloat("pitch", player.getXRot());
        tag.putFloat("health", player.getHealth());
        tag.putFloat("absorption", player.getAbsorptionAmount());
        tag.putInt("air", player.getAirSupply());
        tag.putInt("fire", player.getRemainingFireTicks());
        tag.putInt("frozen", player.getTicksFrozen());
        tag.putString("gameMode", player.gameMode().getName());
        tag.putInt("xp", player.experienceLevel);
        tag.putInt("totalXp", player.totalExperience);
        tag.putFloat("xpProgress", player.experienceProgress);
        return tag;
    }

    private static void saveReturnPoint(ServerPlayer player) throws IOException {
        Path dest = returnFile(player);
        if (Files.exists(dest)) {
            return;
        }
        if (isArena(player.level())) {
            throw new IllegalStateException("Der Rückkehrpunkt für diese Welt fehlt.");
        }

        Files.createDirectories(dest.getParent());
        Path tempFile = Files.createTempFile(dest.getParent(), "return-", ".tmp");
        try {
            NbtIo.writeCompressed(capture(player), tempFile);
            try {
                Files.move(tempFile, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, dest);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public static Vec3 prepareRound(ServerPlayer player, TrainingSettings settings, DrillOptions options, KitLoadout loadout) {
        MinecraftServer server = player.level().getServer();
        ServerLevel arena = server.getLevel(KEY);
        if (arena == null) {
            throw new IllegalStateException("Arena-Dimension fehlt. Prüfe die aktivierten Weltdatenpakete.");
        }

        if (player.isPassenger() || player.isSleeping()) {
            throw new IllegalStateException("Bitte stehe zum Start frei in der Welt.");
        }

        ServerPlayer requester = player;
        if (arena.players().stream().anyMatch(occupant -> occupant != requester)) {
            throw new IllegalStateException("Die Trainingsarena ist bereits belegt.");
        }

        try {
            saveReturnPoint(player);
        } catch (IOException error) {
            throw new IllegalStateException("Der Rückkehrpunkt konnte nicht gesichert werden.", error);
        }

        double approachRad = Math.toRadians(options.approachAngle());
        int startZ = START_Z;
        double playerX = 0.5;
        double playerZ = startZ + 0.5;
        double botX = 0.5 + options.sideOffset() + Math.sin(approachRad) * options.startDistance();
        double botZ = 0.5 + startZ + Math.cos(approachRad) * options.startDistance();
        if (options.drill() == Drill.BODY_PEARL) {
            // Bot always spawns at arena center above; player spawns across the full reachable arena floor.
            botX = 0.5;
            botZ = 0.5;
            var rng = player.getRandom();
            double simY = Math.max(1.0, options.botElevation());
            double simVy = -0.10;
            int fallTicks = 0;
            while (simY > 1.5 && fallTicks < 120) {
                simY += simVy;
                simVy = (simVy - 0.08) * 0.98;
                fallTicks++;
            }
            double minDist = Math.max(4.0, options.startDistance());
            double pearlRange = Math.max(minDist + 6.0, (fallTicks - 4.0) * 1.32);
            double mapCap = options.largeArena() ? 42.0 : 19.0;
            double maxDist = Math.max(minDist + 4.0, Math.min(mapCap, pearlRange));
            int px = (int) Math.round(minDist);
            int pz = 0;
            for (int tries = 0; tries < 24; tries++) {
                double dist = minDist + rng.nextDouble() * (maxDist - minDist);
                double theta = rng.nextDouble() * Math.PI * 2.0;
                int cx = (int) Math.round(Math.sin(theta) * dist);
                int cz = (int) Math.round(Math.cos(theta) * dist);
                if (Math.hypot(cx, cz) >= Math.max(3.5, minDist - 0.5) && insideBodyPearlFloor(cx, cz, options.largeArena())) {
                    px = cx;
                    pz = cz;
                    break;
                }
            }
            playerX = px + 0.5;
            playerZ = pz + 0.5;
        } else if (options.randomBlockPos()) {
            // Randomly shift bot to a block edge/corner (±0.30) to train trajectory reading in Stun-Web.
            var rng = player.getRandom();
            botX += (rng.nextInt(3) - 1) * 0.30;
            botZ += (rng.nextInt(3) - 1) * 0.30;
        }
        boolean isLedgeDash = options.drill() == Drill.LEDGE_DASH;
        int playerHill = options.drill() == Drill.BODY_PEARL && options.largeArena()
            ? ForgeArena.largeHillOffset((int) Math.floor(playerX), (int) Math.floor(playerZ))
            : 0;
        int botHill = options.largeArena() && !settings.maceCenterLocked() && !isLedgeDash
            ? ForgeArena.largeHillOffset((int) Math.floor(botX), (int) Math.floor(botZ))
            : 0;
        Vec3 botTarget = new Vec3(botX, FLOOR + 1.0 + botHill, botZ);
        reset(arena, ArenaRound.layoutOptions(options, settings), options.largeArena(), BlockPos.containing(botTarget), startZ);
        uhc = settings.mode() == TrainingSettings.Mode.UHC;
        TrainingKit.begin(player, settings.mode(), loadout);
        player.setGameMode(GameType.SURVIVAL);

        int playerElevation = isLedgeDash ? options.ledgeHeight() : ArenaRound.elevation(options);
        if (!ArenaRound.enterRound(player, arena, playerX, FLOOR + 1 + playerHill + playerElevation, playerZ, Set.of(), 0.0F, 0.0F, true, botTarget, settings)) {
            throw new IllegalStateException("Der Wechsel in die Arena ist fehlgeschlagen.");
        }

        player = currentPlayer(player);
        resetMotion(player);
        player.setNoGravity(true);
        if (airStart(options)) {
            player.setOnGround(false);
            player.hurtMarked = true;
        }

        TagValueOutput defaultFood = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        new FoodData().addAdditionalSaveData(defaultFood);
        player.getFoodData().readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), defaultFood.buildResult()));
        player.clearFire();
        player.setTicksFrozen(0);
        player.setAirSupply(player.getMaxAirSupply());
        player.setAbsorptionAmount(0.0F);
        player.setHealth(Math.min(player.getMaxHealth(), options.startHealth()));
        player.setExperienceLevels(0);
        player.setExperiencePoints(0);
        player.totalExperience = 0;
        player.invulnerableTime = 0;
        player.hurtTime = 0;

        int botElevation = options.botElevation() > 0
            ? options.botElevation()
            : (isLedgeDash ? options.ledgeHeight() : -options.holeDepth());
        return ArenaRound.stageScenario(botTarget.add(0.0, botElevation, 0.0), player, options, settings);
    }

    private static boolean insideBodyPearlFloor(int x, int z, boolean large) {
        int maxAxis = Math.max(Math.abs(x), Math.abs(z));
        int manhattan = Math.abs(x) + Math.abs(z);
        return large ? (maxAxis <= 42 && manhattan <= 66) : (maxAxis <= 17 && manhattan <= 28);
    }

    private static void resetMotion(ServerPlayer player) {
        player.stopUsingItem();
        player.stopFallFlying();
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.resetCurrentImpulseContext();
        ((AttackStrengthAccess) player).pvptrainer$setAttackStrengthTicker(200);
        ItemCooldowns cooldowns = player.getCooldowns();
        for (Identifier group : List.copyOf(((CooldownAccess) cooldowns).pvptrainer$groups().keySet())) {
            cooldowns.removeCooldown(group);
        }
    }

    private static void reset(ServerLevel arena, DrillOptions options, boolean largeArena, BlockPos botPos, int startZ) {
        int minChunk = largeArena ? -4 : -2;
        int maxChunk = largeArena ? 3 : 1;
        for (int chunkX = minChunk; chunkX <= maxChunk; chunkX++) {
            for (int chunkZ = minChunk; chunkZ <= maxChunk; chunkZ++) {
                arena.getChunk(chunkX, chunkZ);
            }
        }

        List<Entity> nonPlayers = new ArrayList<>();
        arena.getAllEntities().forEach(entity -> {
            if (!(entity instanceof ServerPlayer)) {
                nonPlayers.add(entity);
            }
        });
        nonPlayers.forEach(Entity::discard);
        ForgeArena.restore(arena, largeArena);

        if (options.drill() == Drill.LEDGE_DASH) {
            for (int x = -1; x <= Math.max(1, botPos.getX() + 1); x++) {
                for (int z = startZ - 2; z <= Math.max(startZ, botPos.getZ()) + 1; z++) {
                    for (int y = 1; y <= options.ledgeHeight(); y++) {
                        arena.setBlockAndUpdate(new BlockPos(x, FLOOR + y, z), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                    }
                }
            }
        }

        for (int depth = 1; depth <= options.holeDepth(); depth++) {
            arena.setBlockAndUpdate(botPos.below(depth), Blocks.AIR.defaultBlockState());
        }

        if (options.cover() > 0) {
            int coverZ = Math.floorDiv(startZ + botPos.getZ(), 2);
            int coverWidth = options.cover() == 1 ? 1 : 3;
            for (int x = -coverWidth; x < 0; x++) {
                for (int y = 1; y <= 2; y++) {
                    arena.setBlockAndUpdate(new BlockPos(x, FLOOR + y, coverZ), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                }
            }
        }

        if (options.startHeight() > 0 && !airStart(options)) {
            for (int x = -1; x <= 1; x++) {
                for (int z = startZ - 2; z <= startZ; z++) {
                    arena.setBlockAndUpdate(new BlockPos(x, FLOOR + options.startHeight(), z), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                }
            }
        }

        if (options.preparedBase() && options.drill() == Drill.LEDGE_DASH) {
            arena.setBlockAndUpdate(botPos.offset(-2, -1, 0), Blocks.OBSIDIAN.defaultBlockState());
        } else if (options.preparedBase() && !options.drill().needsCornerWalls()) {
            arena.setBlockAndUpdate(botPos.offset(1, -1, 0), Blocks.OBSIDIAN.defaultBlockState());
        }

        if (options.drill() == Drill.WEB_DRAIN) {
            arena.setBlockAndUpdate(botPos, Blocks.COBWEB.defaultBlockState());
        }
        if (options.drill() == Drill.ANTI_DRAIN) {
            arena.setBlockAndUpdate(new BlockPos(0, FLOOR + 1, startZ), Blocks.COBWEB.defaultBlockState());
        }
    }

    public static boolean airStart(DrillOptions options) {
        boolean base = options.drill() == Drill.STUN_SLAM
            && (options.startHeight() > 0 || options.slamScene() == DrillOptions.SlamScene.PAIRED_FALL);
        return ArenaRound.airStart(base, options);
    }

    public static void leave(ServerPlayer player) {
        player = currentPlayer(player);
        if (player == null) {
            return;
        }

        MinecraftServer server = player.level().getServer();
        if (!server.isSameThread()) {
            throw new IllegalStateException("Die Rückkehr muss auf dem Serverthread ausgeführt werden.");
        }

        if (player.connection == null || !player.connection.isAcceptingMessages() || player.isRemoved() || server.isStopped()) {
            return;
        }

        Path dest = returnFile(player);
        if (!Files.exists(dest)) {
            return;
        }

        try {
            CompoundTag tag = NbtIo.readCompressed(dest, NbtAccounter.create(1048576L));
            MinecraftServer activeServer = player.level().getServer();
            String dimensionName = tag.getString("dimension").orElseThrow();
            ServerLevel originLevel = activeServer.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimensionName)));
            if (originLevel == null) {
                throw new IllegalStateException("Ursprüngliche Dimension fehlt: " + dimensionName);
            }

            TrainingKit.SavedInventory savedInventory = TrainingKit.readBackup(player);
            resetMotion(player);
            player.setNoGravity(tag.getBooleanOr("noGravity", false));
            boolean teleported = player.teleportTo(
                originLevel,
                tag.getDouble("x").orElseThrow(),
                tag.getDouble("y").orElseThrow(),
                tag.getDouble("z").orElseThrow(),
                Set.of(),
                tag.getFloatOr("yaw", 0.0F),
                tag.getFloatOr("pitch", 0.0F),
                true
            );
            if (!teleported) {
                throw new IllegalStateException("Rückkehr fehlgeschlagen.");
            }

            player = currentPlayer(player);
            resetMotion(player);
            player.setNoGravity(tag.getBooleanOr("noGravity", false));
            CompoundTag cooldownsTag = tag.getCompoundOrEmpty("cooldowns");
            for (String cooldownKey : cooldownsTag.keySet()) {
                player.getCooldowns().addCooldown(Identifier.parse(cooldownKey), cooldownsTag.getIntOr(cooldownKey, 0));
            }

            player.setGameMode(GameType.byName(tag.getStringOr("gameMode", "survival")));
            if (savedInventory != null) {
                savedInventory.restore(player);
            }

            ValueInput valueInput = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), tag);
            player.setRespawnPosition(valueInput.read("returnSpawn", RespawnConfig.CODEC).orElse(null), false);
            valueInput.read("abilities", Packed.CODEC).ifPresent(player.getAbilities()::apply);
            player.onUpdateAbilities();
            ArenaRound.syncReturnMotion(player);
            player.getFoodData().readAdditionalSaveData(valueInput);
            player.setHealth(tag.getFloatOr("health", 20.0F));
            player.setAbsorptionAmount(tag.getFloatOr("absorption", 0.0F));
            player.setAirSupply(tag.getIntOr("air", 300));
            player.setRemainingFireTicks(tag.getIntOr("fire", 0));
            player.setTicksFrozen(tag.getIntOr("frozen", 0));
            player.setExperienceLevels(tag.getIntOr("xp", 0));
            player.experienceProgress = tag.getFloatOr("xpProgress", 0.0F);
            player.totalExperience = tag.getIntOr("totalXp", 0);
            if (!ArenaRecoverySave.persist(activeServer, player)) {
                throw new IOException("Spielstand nicht geschrieben.");
            }

            Files.deleteIfExists(dest);
            TrainingKit.completeRestore(player);
            uhc = false;
        } catch (Exception error) {
            throw new IllegalStateException("Dein Weltzustand konnte nicht vollständig wiederhergestellt werden. Die Sicherung bleibt erhalten.", error);
        }
    }

    public static ServerPlayer currentPlayer(ServerPlayer player) {
        return player != null && player.connection != null ? player.connection.player : player;
    }
}
