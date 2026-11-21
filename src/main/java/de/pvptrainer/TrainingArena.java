package de.pvptrainer;

import com.mojang.logging.LogUtils;
import de.pvptrainer.hooks.CooldownAccess;
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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Join;
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

   static void register() {
      ArenaRound.register();
      UseBlockCallback.EVENT
         .register(
            (UseBlockCallback)(var0, var1, var2, var3) -> (InteractionResult)(var1.dimension() == KEY
                  && var1.getBlockState(var3.getBlockPos()).is(Blocks.ENDER_CHEST)
               ? InteractionResult.FAIL
               : InteractionResult.PASS)
         );
      ServerPlayConnectionEvents.JOIN.register((Join)(var0, var1, var2) -> ArenaRound.joined(var0.player));
   }

   public static boolean isArena(Level var0) {
      return var0.dimension() == KEY;
   }

   public static boolean isArena(ServerLevel var0) {
      return isArena((Level)var0);
   }

   public static boolean suppressRegeneration(ServerPlayer var0) {
      return uhc && isArena(var0.level());
   }

   private static Path file(ServerPlayer var0) {
      return var0.level().getServer().getWorldPath(LevelResource.ROOT).resolve("pvptrainer").resolve("return-" + var0.getUUID() + ".dat");
   }

   static boolean hasReturnPoint(ServerPlayer var0) {
      return Files.exists(file(var0));
   }

   static boolean recoverPending(ServerPlayer var0) {
      if (!hasReturnPoint(var0)) {
         return ArenaRound.missingReturn(var0);
      }

      try {
         leave(var0);
         var0 = currentPlayer(var0);
         return !hasReturnPoint(var0);
      } catch (RuntimeException var2) {
         LOG.error("MacherBot: Rückkehrdaten blieben für die Wiederherstellung erhalten", var2);
         var0.sendSystemMessage(Component.literal(var2.getMessage()));
         return false;
      }
   }

   private static CompoundTag capture(ServerPlayer var0) {
      TagValueOutput var1 = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, var0.registryAccess());
      var0.getFoodData().addAdditionalSaveData(var1);
      var1.store("abilities", Packed.CODEC, var0.getAbilities().pack());
      if (var0.getRespawnConfig() != null) {
         var1.store("returnSpawn", RespawnConfig.CODEC, var0.getRespawnConfig());
      }

      CompoundTag var2 = var1.buildResult();
      CooldownAccess var3 = (CooldownAccess)var0.getCooldowns();
      CompoundTag var4 = new CompoundTag();
      var3.pvptrainer$groups().forEach((var2x, var3x) -> {
         int var4x = ((CooldownAccess.Timer)var3x).pvptrainer$end() - var3.pvptrainer$clock();
         if (var4x > 0) {
            var4.putInt(var2x.toString(), var4x);
         }
      });
      var2.put("cooldowns", var4);
      var2.putBoolean("noGravity", var0.isNoGravity());
      var2.putString("dimension", var0.level().dimension().identifier().toString());
      var2.putDouble("x", var0.getX());
      var2.putDouble("y", var0.getY());
      var2.putDouble("z", var0.getZ());
      var2.putFloat("yaw", var0.getYRot());
      var2.putFloat("pitch", var0.getXRot());
      var2.putFloat("health", var0.getHealth());
      var2.putFloat("absorption", var0.getAbsorptionAmount());
      var2.putInt("air", var0.getAirSupply());
      var2.putInt("fire", var0.getRemainingFireTicks());
      var2.putInt("frozen", var0.getTicksFrozen());
      var2.putString("gameMode", var0.gameMode().getName());
      var2.putInt("xp", var0.experienceLevel);
      var2.putInt("totalXp", var0.totalExperience);
      var2.putFloat("xpProgress", var0.experienceProgress);
      return var2;
   }

   private static void saveReturnPoint(ServerPlayer var0) throws IOException {
      Path var1 = file(var0);
      if (!Files.exists(var1)) {
         if (isArena(var0.level())) {
            throw new IllegalStateException("Der Rückkehrpunkt für diese Welt fehlt.");
         }

         Files.createDirectories(var1.getParent());
         Path var2 = Files.createTempFile(var1.getParent(), "return-", ".tmp");

         try {
            NbtIo.writeCompressed(capture(var0), var2);

            try {
               Files.move(var2, var1, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException var6) {
               Files.move(var2, var1);
            }
         } finally {
            Files.deleteIfExists(var2);
         }
      }
   }

   static Vec3 prepareRound(ServerPlayer var0, TrainingSettings var1, DrillOptions var2, KitLoadout var3) {
      MinecraftServer var4 = var0.level().getServer();
      ServerLevel var5 = var4.getLevel(KEY);
      if (var5 == null) {
         throw new IllegalStateException("Arena-Dimension fehlt. Prüfe die aktivierten Weltdatenpakete.");
      }

      if (!var0.isPassenger() && !var0.isSleeping()) {
         ServerPlayer requester = var0;
         if (var5.players().stream().anyMatch(var1x -> var1x != requester)) {
            throw new IllegalStateException("Die Trainingsarena ist bereits belegt.");
         }

         try {
            saveReturnPoint(var0);
         } catch (IOException var14) {
            throw new IllegalStateException("Der Rückkehrpunkt konnte nicht gesichert werden.", var14);
         }

         double var6 = Math.toRadians(var2.approachAngle());
         byte var8 = -4;
         Vec3 var9 = new Vec3(0.5 + var2.sideOffset() + Math.sin(var6) * var2.startDistance(), 65.0, 0.5 + var8 + Math.cos(var6) * var2.startDistance());
         boolean var10 = var2.drill() == Drill.LEDGE_DASH;
         reset(var5, ArenaRound.layoutOptions(var2, var1), BlockPos.containing(var9), var8);
         uhc = var1.mode() == TrainingSettings.Mode.UHC;
         TrainingKit.begin(var0, var1.mode(), var3);
         var0.setGameMode(GameType.SURVIVAL);
         int var11 = var10 ? var2.ledgeHeight() : ArenaRound.elevation(var2);
         if (!ArenaRound.enterRound(var0, var5, 0.5, 65 + var11, var8 + 0.5, Set.of(), 0.0F, 0.0F, true, var9, var1)) {
            throw new IllegalStateException("Der Wechsel in die Arena ist fehlgeschlagen.");
         }

         var0 = currentPlayer(var0);
         resetMotion(var0);
         var0.setNoGravity(true);
         if (airStart(var2)) {
            var0.setOnGround(false);
            var0.hurtMarked = true;
         }

         TagValueOutput var12 = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
         new FoodData().addAdditionalSaveData(var12);
         var0.getFoodData().readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, var0.registryAccess(), var12.buildResult()));
         var0.clearFire();
         var0.setTicksFrozen(0);
         var0.setAirSupply(var0.getMaxAirSupply());
         var0.setAbsorptionAmount(0.0F);
         var0.setHealth(Math.min(var0.getMaxHealth(), var2.startHealth()));
         var0.setExperienceLevels(0);
         var0.setExperiencePoints(0);
         var0.totalExperience = 0;
         var0.invulnerableTime = 0;
         var0.hurtTime = 0;
         int var13 = var2.botElevation() > 0 ? var2.botElevation() : (var10 ? var2.ledgeHeight() : -var2.holeDepth());
         return ArenaRound.stageScenario(var9.add(0.0, var13, 0.0), var0, var2, var1);
      } else {
         throw new IllegalStateException("Bitte stehe zum Start frei in der Welt.");
      }
   }

   private static void resetMotion(ServerPlayer var0) {
      var0.stopUsingItem();
      var0.stopFallFlying();
      var0.setDeltaMovement(Vec3.ZERO);
      var0.resetFallDistance();
      var0.resetCurrentImpulseContext();
      var0.resetAttackStrengthTicker();
      ItemCooldowns var1 = var0.getCooldowns();

      for (Identifier var2 : List.copyOf(((CooldownAccess)var1).pvptrainer$groups().keySet())) {
         var1.removeCooldown(var2);
      }
   }

   private static void reset(ServerLevel var0, DrillOptions var1, BlockPos var2, int var3) {
      for (int var4 = -2; var4 <= 1; var4++) {
         for (int var5 = -2; var5 <= 1; var5++) {
            var0.getChunk(var4, var5);
         }
      }

      ArrayList<Entity> var9 = new ArrayList<>();
      var0.getAllEntities().forEach(var1x -> {
         if (!(var1x instanceof ServerPlayer)) {
            var9.add(var1x);
         }
      });
      var9.forEach(Entity::discard);
      ForgeArena.restore(var0);
      if (var1.drill() == Drill.LEDGE_DASH) {
         for (int var10 = -1; var10 <= Math.max(1, var2.getX() + 1); var10++) {
            for (int var6 = var3 - 2; var6 <= Math.max(var3, var2.getZ()) + 1; var6++) {
               for (int var7 = 1; var7 <= var1.ledgeHeight(); var7++) {
                  var0.setBlockAndUpdate(new BlockPos(var10, 64 + var7, var6), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
               }
            }
         }
      }

      for (int var11 = 1; var11 <= var1.holeDepth(); var11++) {
         var0.setBlockAndUpdate(var2.below(var11), Blocks.AIR.defaultBlockState());
      }

      if (var1.cover() > 0) {
         int var12 = Math.floorDiv(var3 + var2.getZ(), 2);
         int var14 = var1.cover() == 1 ? 1 : 3;

         for (int var16 = -var14; var16 < 0; var16++) {
            for (int var8 = 1; var8 <= 2; var8++) {
               var0.setBlockAndUpdate(new BlockPos(var16, 64 + var8, var12), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            }
         }
      }

      if (var1.startHeight() > 0 && !airStart(var1)) {
         for (int var13 = -1; var13 <= 1; var13++) {
            for (int var15 = var3 - 2; var15 <= var3; var15++) {
               var0.setBlockAndUpdate(new BlockPos(var13, 64 + var1.startHeight(), var15), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            }
         }
      }

      if (var1.preparedBase() && var1.drill() == Drill.LEDGE_DASH) {
         var0.setBlockAndUpdate(var2.offset(-2, -1, 0), Blocks.OBSIDIAN.defaultBlockState());
      } else if (var1.preparedBase()) {
         var0.setBlockAndUpdate(var2.offset(1, -1, 0), Blocks.OBSIDIAN.defaultBlockState());
      }

      if (var1.drill() == Drill.WEB_DRAIN) {
         var0.setBlockAndUpdate(var2, Blocks.COBWEB.defaultBlockState());
      }
   }

   static boolean airStart(DrillOptions var0) {
      boolean base = var0.drill() == Drill.STUN_SLAM && (var0.startHeight() > 0 || var0.slamScene() == DrillOptions.SlamScene.PAIRED_FALL);
      return ArenaRound.airStart(base, var0);
   }

   static void leave(ServerPlayer var0) {
      var0 = currentPlayer(var0);
      if (var0 != null) {
         MinecraftServer var1 = var0.level().getServer();
         if (!var1.isSameThread()) {
            throw new IllegalStateException("Die Rückkehr muss auf dem Serverthread ausgeführt werden.");
         }

         if (var0.connection != null && var0.connection.isAcceptingMessages() && !var0.isRemoved() && !var1.isStopped()) {
            Path var2 = file(var0);
            if (Files.exists(var2)) {
               try {
                  CompoundTag var3 = NbtIo.readCompressed(var2, NbtAccounter.create(1048576L));
                  MinecraftServer var4 = var0.level().getServer();
                  String var5 = (String)var3.getString("dimension").orElseThrow();
                  ServerLevel var6 = var4.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(var5)));
                  if (var6 == null) {
                     throw new IllegalStateException("Ursprüngliche Dimension fehlt: " + var5);
                  }

                  TrainingKit.SavedInventory var7 = TrainingKit.readBackup(var0);
                  resetMotion(var0);
                  var0.setNoGravity(var3.getBooleanOr("noGravity", false));
                  boolean var8 = var0.teleportTo(
                     var6,
                     (Double)var3.getDouble("x").orElseThrow(),
                     (Double)var3.getDouble("y").orElseThrow(),
                     (Double)var3.getDouble("z").orElseThrow(),
                     Set.of(),
                     var3.getFloatOr("yaw", 0.0F),
                     var3.getFloatOr("pitch", 0.0F),
                     true
                  );
                  if (!var8) {
                     throw new IllegalStateException("Rückkehr fehlgeschlagen.");
                  }

                  var0 = currentPlayer(var0);
                  resetMotion(var0);
                  var0.setNoGravity(var3.getBooleanOr("noGravity", false));
                  CompoundTag var9 = var3.getCompoundOrEmpty("cooldowns");

                  for (String var10 : var9.keySet()) {
                     var0.getCooldowns().addCooldown(Identifier.parse(var10), var9.getIntOr(var10, 0));
                  }

                  var0.setGameMode(GameType.byName(var3.getStringOr("gameMode", "survival")));
                  if (var7 != null) {
                     var7.restore(var0);
                  }

                  ValueInput var15 = TagValueInput.create(ProblemReporter.DISCARDING, var0.registryAccess(), var3);
                  var0.setRespawnPosition((RespawnConfig)var15.read("returnSpawn", RespawnConfig.CODEC).orElse(null), false);
                  var15.read("abilities", Packed.CODEC).ifPresent(var0.getAbilities()::apply);
                  var0.onUpdateAbilities();
                  ArenaRound.syncReturnMotion(var0);
                  var0.getFoodData().readAdditionalSaveData(var15);
                  var0.setHealth(var3.getFloatOr("health", 20.0F));
                  var0.setAbsorptionAmount(var3.getFloatOr("absorption", 0.0F));
                  var0.setAirSupply(var3.getIntOr("air", 300));
                  var0.setRemainingFireTicks(var3.getIntOr("fire", 0));
                  var0.setTicksFrozen(var3.getIntOr("frozen", 0));
                  var0.setExperienceLevels(var3.getIntOr("xp", 0));
                  var0.experienceProgress = var3.getFloatOr("xpProgress", 0.0F);
                  var0.totalExperience = var3.getIntOr("totalXp", 0);
                  if (!ArenaRecoverySave.persist(var4, var0)) {
                     throw new IOException("Spielstand nicht geschrieben.");
                  }

                  Files.deleteIfExists(var2);
                  TrainingKit.completeRestore(var0);
                  uhc = false;
               } catch (Exception var12) {
                  throw new IllegalStateException("Dein Weltzustand konnte nicht vollständig wiederhergestellt werden. Die Sicherung bleibt erhalten.", var12);
               }
            }
         }
      }
   }

   static ServerPlayer currentPlayer(ServerPlayer var0) {
      return var0 != null && var0.connection != null ? var0.connection.player : var0;
   }
}
