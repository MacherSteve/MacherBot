package de.pvptrainer;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData.DataValue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.phys.HitResult.Type;
import org.slf4j.LoggerFactory;

final class TrainingSession {
   private volatile TrainingStatus snapshot = TrainingStatus.idle();
   private UUID ownerId;
   private ServerPlayer owner;
   private ServerLevel arena;
   private TrainingSettings settings;
   private DrillOptions options;
   private TrainingSession.TrainingBot bot;
   private BotEquipment equipment;
   private TrainingTactics tactics;
   private DuelFootwork duelFootwork;
   private Vec3 maceCenter;
   private TrainingSettings movementSettings;
   private DrillAttempt attempt;
   private DrillHistory history;
   private BotTelemetry telemetry;
   private String historyKey;
   private volatile TrainingSettings lastDuelSettings;
   private String roundBotFingerprint;
   private String roundRules;
   private volatile TrainingSession.Rules liveRules = new TrainingSession.Rules("normal", true);
   private ServerLevel rulesLevel;
   private List<DrillHistory.Result> recentResults = List.of();
   private int round;
   private int hits;
   private int pops;
   private int roundTick;
   private int respawnIn;
   private int shieldReadyAt;
   private boolean saved;
   private boolean ownerDefeated;
   private boolean resetAfterPop;
   private float roundStartHealth;
   private float lastBotDamage;
   private int wins;
   private int attempts;
   private int median;
   private int previousRepetitionAt;
   private DrillAttempt secondarySlam;
   private int secondarySlamAt, secondaryPopAt;
   private float swingCharge;
   private float ownCrystalLoss;
   private boolean targetInAirBeforeBlast;
   private int hurtWindowRemaining;
   private String displayedFault;
   private boolean waitingForStart;
   private long roundSequence;
   private long startToken;
   private int waitingTicks;
   private float priorTickRate;
   private float appliedTickRate;
   private Vec3 suspendedAt;
   private TrainingSession.RoundSelection selection;
   private TrainingSession.RoundSelection lastPlayed;
   private Vec3 refillRecoverySpot;
   private int directComboClick = -1;
   private long ownerAttackSequence;
   private long webClickOnBot;
   private int popsBeforeCrystal;
   private WindCharge targetCharge;
   private final WindPearlCatch windCatch = new WindPearlCatch();
   private enum WindThrow { LIFT, PEARL, CATCH }
   private WindThrow windThrow;
   private static final int WIND_ITEM_SWITCH_TICKS = 2;
   private static final int MIN_APEX_CATCH_DELAY = 5;
   private int windEquipAt;
   private boolean waitingForWindApex, windLiftAirborne;
   private int nextWindLaunch = 1;
   private BlockPos windPlatform;
   private double windBaseY;
   private WindHeight windHeight;
   private int windThrowAt, windEquipShieldAt, windGuardAt;
   private double obiBestSeconds;
   private boolean webChainActive, webWindPrimed, webWindAirborne;
   private boolean webFlightAttempted, webFlightJudged;
   private int webLaunchTick;
   private int webLandingTick = -1;
   private int webLastSmashTick = -1000;
   private double webLandingY;
   private boolean airborneAxeStun;
   private boolean scoredWindFlight;

   void rememberStartedRound() {
      this.lastPlayed = this.selection;
   }

   void restart(MinecraftServer var1, ServerPlayer var2) {
      if (this.lastPlayed == null) {
         ArenaRound.restartSaved(this, var1, var2);
      } else {
         // The exercise repeats with its last rules. Kits can be edited or deleted between rounds.
         this.start(var1, var2.getUUID(), this.lastPlayed.settings(), this.lastPlayed.options());
      }
   }

   TrainingStatus status() {
      return this.snapshot;
   }

   void start(MinecraftServer var1, UUID var2, TrainingSettings var3) {
      this.start(var1, var2, var3, DrillOptions.forDrill(Drill.DUEL));
   }

   void start(MinecraftServer var1, UUID var2, TrainingSettings var3, DrillOptions var4) {
      ServerPlayer var5 = var1.getPlayerList().getPlayer(var2);
      if (var5 != null && !var5.isRemoved()) {
         if (!var5.isAlive()) {
            notifyPlayer(var5, "Bitte zuerst in deiner Welt respawnen.");
         } else {
            RegistryAccess var6 = var5.registryAccess();
            this.start(
               var1,
               var5,
               new TrainingSession.RoundSelection(
                  var3,
                  var4.drill().supports(var3.mode()) ? var4 : DrillOptions.defaults(var3.mode()),
                  KitLibrary.personalized(var6, var3.mode(), KitLibrary.selected(var6, var3.mode(), false).id()),
                  TrainingKit.botLoadout(var5, var3.mode())
               )
            );
         }
      }
   }

   private void start(MinecraftServer var1, ServerPlayer var2, TrainingSession.RoundSelection var3) {
      String missing = BotEquipment.missingRequirement(var3.botKit(), var3.options());
      if (var3.options().drill() == Drill.PERFECT_WIND_CHARGE && var3.playerKit().slots().stream().noneMatch(stack -> stack.is(Items.WIND_CHARGE)))
         missing = "Für Perfect Wind Charge braucht dein Spieler-Kit Windladungen.";
      if (missing != null) {
         notifyPlayer(var2, missing);
         return;
      }
      UUID var4 = var2.getUUID();
      if (this.owner != null && this.owner != var2) {
         this.finish(this.owner, "Training beendet.");
      }

      if (this.ownerId != null || TrainingArena.recoverPending(var2)) {
         var2 = TrainingArena.currentPlayer(var2);
         this.recordInterruption("Versuch wiederholt");
         this.releaseBot();
         this.ownerId = var4;
         this.owner = var2;
         this.selection = var3;
         this.settings = var3.settings();
         this.options = var3.options();
         this.round = this.hits = this.pops = 0;
         if (this.history == null) {
            this.history = new DrillHistory(var1.getWorldPath(LevelResource.ROOT).resolve("data/pvptrainer/serien-" + var4 + ".json"));
         }

         this.spawnRound(var2);
      }
   }

   private void spawnRound(ServerPlayer var1) {
      this.settings = this.selection.settings();
      if (options.drill() == Drill.DUEL) {
         if (settings.duelBand() == TrainingSettings.DuelBand.AUTO) {
            settings = settings.withDuelBand(var1.getRandom().nextBoolean()
               ? TrainingSettings.DuelBand.HIGH : TrainingSettings.DuelBand.LOW);
         }
         lastDuelSettings = settings;
      }
      this.respawnIn = this.shieldReadyAt = this.previousRepetitionAt = this.roundTick = 0;
      this.ownerDefeated = this.resetAfterPop = this.saved = false;
      this.attempt = null;
      this.historyKey = null;
      this.displayedFault = null;
      secondarySlam = null;
      secondaryPopAt = 0;

      try {
         TrainingSettings arenaSettings = options.drill().centeredMaceDrill()
            ? settings.withMaceCenterLocked(options.drill() == Drill.LOW_WEB_SMASH || options.windCenter()) : settings;
         Vec3 var2 = TrainingArena.prepareRound(var1, arenaSettings, this.options, this.selection.playerKit());
         maceCenter = arenaSettings.maceCenterLocked() || options.drill() == Drill.WIND_STUN_SLAM && options.slamScene() != DrillOptions.SlamScene.FREE ? var2 : null;
         windBaseY = var2.y - options.botElevation();
         movementSettings = maceCenter == null ? settings : settings.withMovement(TrainingSettings.Movement.STILL);
         var1 = TrainingArena.currentPlayer(var1);
         this.owner = var1;
         this.arena = var1.level();
         TrainingSession.TrainingBot var3 = new TrainingSession.TrainingBot(this.arena);
         this.waitingForStart = true;
         this.waitingTicks = 0;
         this.startToken = ++this.roundSequence;
         this.suspendedAt = var1.position();
         this.refillRecoverySpot = this.options.drill().totemDefense() ? var2 : null;
         var3.snapTo(var2, var1.getYRot() + 180.0F, 0.0F);
         var3.setNoGravity(options.drill() != Drill.WIND_STUN_SLAM);
         var3.setOnGround(this.options.slamScene() == DrillOptions.SlamScene.GROUND_LOCK || maceCenter != null && options.botElevation() == 0);
         var3.lookAt(Anchor.EYES, var1.getEyePosition());
         var3.setYHeadRot(var3.getYRot());
         var3.setYBodyRot(var3.getYRot());
         var3.getAttribute(Attributes.MAX_HEALTH).setBaseValue(this.settings.maxHealth());
         var3.setHealth(this.settings.maxHealth());

         var3.setSpeed(this.options.drill() == Drill.DUEL ? 0.1F : switch (this.settings.difficulty()) {
            case EASY -> 0.065F;
            case NORMAL -> 0.095F;
            case HARD -> 0.125F;
         });
         KitLoadout var4 = this.selection.botKit();
         this.equipment = new BotEquipment(var3, var4, this.options, settings.mode());
         this.tactics = new TrainingTactics(this.equipment, this.options);
         this.duelFootwork = this.options.drill() == Drill.DUEL ? new DuelFootwork(DuelSkill.of(this.settings), var3) : null;
         if (!this.arena.addFreshEntity(var3)) {
            throw new IllegalStateException("Der Bot konnte hier nicht erscheinen.");
         }

         this.bot = var3;
         ArenaRound.staged(var1, this.bot, this.settings);
         this.keepBotLoaded();
         TrainingResources.begin(var1, var3, this.options, this.settings.mode());
         if (this.options.drill() == Drill.STUN_SLAM) {
            BlocksAttacks shield = var3.getItemInHand(this.equipment.shieldHand).get(DataComponents.BLOCKS_ATTACKS);
            if (shield != null) var3.pauseShield(0, shield, true);
         }
         this.roundStartHealth = var1.getHealth();
         this.round++;
         this.rulesLevel = this.arena;
         this.liveRules = readRules(this.arena);
         this.roundRules = this.liveRules.key(this.settings.mode());
         this.roundBotFingerprint = KitLibrary.fingerprint(var1.registryAccess(), var4);
         this.historyKey = DrillHistory.key(
            this.settings, this.options, TrainingKit.startingFingerprint(var1) + "/" + this.roundBotFingerprint + "|" + this.roundRules
         );
         double var5 = -1.0;

         for (DrillHistory.Result var7 : this.history.get(this.historyKey)) {
            if (var7.controlDistance() >= 0.0) {
               var5 = var7.controlDistance();
            }
         }

         this.attempt = new DrillAttempt(this.options, var5, this.round - 1);
         if (options.drill() == Drill.OBI_TRAP) {
            obiBestSeconds = TrainingProgress.bestTrapTime(ownerId, historyKey, history.get(historyKey), options.tickRate());
         }
         if (options.drill() == Drill.LOW_WEB_SMASH) TrainingProgress.endAirChain(ownerId, historyKey);
         if (options.drill() == Drill.WIND_STUN_SLAM && options.slamScene() == DrillOptions.SlamScene.AIR_LOCK) {
            windPlatform = bot.blockPosition().below();
            arena.setBlockAndUpdate(windPlatform, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
         }
         if (options.drill() == Drill.PERFECT_WIND_CHARGE) windHeight = new WindHeight(var1.getY());
         this.refreshSeries();
         if (this.options.drill() == Drill.RETOTEM) {
            int var10 = TrainingKit.refillSupply(var1);
            if (var10 == 0) {
               this.finish(var1, "Für Manuelles Retotem brauchst du mindestens ein Totem im Spieler-Kit.");
               return;
            }

            this.attempt.startRefill(var10);
         }

         this.rememberStartedRound();
         if (windHeight != null) {
            releaseStart(var1, startToken);
            return;
         }
         this.publish("Bewegen oder klicken zum Starten");
         startPrompt(var1);
      } catch (RuntimeException var9) {
         LoggerFactory.getLogger("pvptrainer").warn("Rundenstart fehlgeschlagen", var9);
         this.finish(var1, var9.getMessage() == null ? "Rundenstart fehlgeschlagen." : var9.getMessage());
      }
   }

   private static TrainingSession.Rules readRules(ServerLevel var0) {
      return new TrainingSession.Rules(var0.getDifficulty().name(), (Boolean)var0.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION));
   }

   String previewKey(TrainingSettings var1, DrillOptions var2, String var3) {
      var previous = lastDuelSettings;
      if (var2.drill() == Drill.DUEL && var1.duelBand() == TrainingSettings.DuelBand.AUTO && previous != null
            && previous.mode() == var1.mode() && previous.duelTier() == var1.duelTier()) {
         var1 = var1.withDuelBand(previous.duelBand());
      }
      return DrillHistory.key(var1, var2, var3 + "|" + this.liveRules.key(var1.mode()));
   }

   void layoutUpdated(ServerPlayer var1, KitLoadout var2) {
      if (this.owns(var1) && this.roundBotFingerprint != null) {
         this.selection = new TrainingSession.RoundSelection(this.selection.settings(), this.options, var2, this.selection.botKit());
         this.lastPlayed = this.selection;
         this.historyKey = DrillHistory.key(
            this.settings, this.options, KitLibrary.fingerprint(var1.registryAccess(), var2) + "/" + this.roundBotFingerprint + "|" + this.roundRules
         );
         this.refreshSeries();
      }
   }

   void tick(MinecraftServer var1) {
      if (var1.getTickCount() % 20 == 0) {
         if (this.bot != null && !this.bot.isRemoved()) this.keepBotLoaded();
         if (this.rulesLevel == null) {
            for (ServerLevel var2 : var1.getAllLevels()) {
               if (TrainingArena.isArena(var2)) {
                  this.rulesLevel = var2;
                  break;
               }
            }
         }

         if (this.rulesLevel != null) {
            this.liveRules = readRules(this.rulesLevel);
         }
      }

      if (this.ownerId != null) {
         ServerPlayer var4 = var1.getPlayerList().getPlayer(this.ownerId);
         if (var4 != null && var4.level() == this.arena && var4.isAlive() && !var4.isSpectator()) {
            if (var4.getY() < this.arena.getMinY() - 64) {
               this.record(false, "Ins Leere gefallen");
               this.finish(var4, "Ins Leere gefallen · Zurück in deiner Welt.");
            } else if (this.ownerDefeated) {
               this.record(false, "Runde verloren");
               this.finish(var4, "Runde " + this.round + " verloren. " + this.attempt.progress());
            } else if (this.respawnIn > 0) {
               if (--this.respawnIn == 0) {
                  this.spawnRound(var4);
               }
            } else {
               if (maceCenter != null && bot != null && bot.isAlive()) {
                  // Position-setting teleports bypass both movement and velocity constraints.
                  double y = options.drill() == Drill.WIND_STUN_SLAM || options.slamScene() == DrillOptions.SlamScene.PAIRED_FALL ? bot.getY() : maceCenter.y;
                  if (bot.distanceToSqr(maceCenter.x, y, maceCenter.z) > 1.0e-12) {
                     bot.snapTo(maceCenter.x, y, maceCenter.z, bot.getYRot(), bot.getXRot());
                  }
               }
               if (this.refillRecoverySpot != null
                  && this.bot != null
                  && !this.bot.isRemoved()
                  && (this.bot.getY() < 60.0 || this.bot.distanceToSqr(this.refillRecoverySpot) > 3600.0)) {
                  this.bot.recoverRefillTarget();
               }

               if (this.bot != null && (!this.bot.isRemoved() || this.bot.isDeadOrDying())) {
                  if (this.bot.isDeadOrDying()) {
                     this.attempt.settleAnchor(var4, this.roundTick + 1);
                  }

                  if (!this.bot.isDeadOrDying() && !this.attempt.done() && !this.resetAfterPop) {
                     if (this.waitingForStart) {
                        if (options.drill() == Drill.WIND_STUN_SLAM) {
                           roundTick++;
                           steer(var4);
                           tickWindTarget();
                           equipment.tick(bot, roundTick, Math.max(shieldReadyAt, windGuardAt));
                        } else this.equipment.tick(this.bot, 0, 0);
                        if (var4.distanceToSqr(this.suspendedAt) > 0.01) {
                           var4.teleportTo(
                              this.arena, this.suspendedAt.x, this.suspendedAt.y, this.suspendedAt.z, Set.of(), var4.getYRot(), var4.getXRot(), true
                           );
                           var4.hurtMarked = true;
                        }

                        var4.setDeltaMovement(Vec3.ZERO);
                        if (++this.waitingTicks % 60 == 0) {
                           startPrompt(var4);
                        }
                     } else {
                        this.roundTick++;
                        RoundPing.observe();
                        if (telemetry != null) telemetry.observe(var1.getTickCount(), TrainingTactics.withinReach(bot, var4, options.botReach()));
                        this.bot.expireWebBreak();
                        if (options.drill() == Drill.LOW_WEB_SMASH) observeWebGround(var4.onGround());
                        if (windHeight != null && windHeight.sample(var4, roundTick)) {
                           double height = windHeight.meters();
                           var result = new DrillHistory.Result(height >= WindHeight.PERFECT, windHeight.duration(roundTick), -1,
                              "Windladung · " + WindHeight.text(height), List.of("Scheitelhöhe " + WindHeight.text(height)));
                           TrainingProgress.record(history, historyKey, result, settings, options, ownerId);
                           refreshSeries();
                        }
                        boolean continuous = options.drill().centeredMaceDrill();
                        this.attempt.tick(continuous ? roundTick - previousRepetitionAt : roundTick, var4, this.bot);
                        if (continuous && attempt.done() && !attempt.unscored()) storeMaceRepetition();
                        if (secondarySlam != null) {
                           secondarySlam.observeSecondarySlam(roundTick, var4);
                           if (secondarySlam.done()) finishSecondarySlam();
                           else if (secondarySlam.unusedSlamWindowExpired()) secondarySlam = null;
                        }
                        if (this.options.drill() == Drill.STUN_SLAM) {
                           this.attempt.observeSlamLanding(var4);
                           if (this.attempt.done()) this.saveResult();
                        } else if (!this.attempt.done() && this.airStartLanded()) {
                           this.attempt.slamLanded();
                        }

                        if (!this.attempt.done()) {
                           this.steer(var4);
                           if (options.drill() == Drill.WIND_STUN_SLAM) tickWindTarget();
                           this.equipment.tick(this.bot, this.roundTick, Math.max(shieldReadyAt, windGuardAt));
                           if (!options.drill().centeredMaceDrill() && options.drill() != Drill.PERFECT_WIND_CHARGE && this.options.slamScene() == DrillOptions.SlamScene.FREE) {
                              this.tactics.practiceTick(this.arena, this.bot, var4, movementSettings, this.attempt);
                           }
                        }

                        if (this.options.hints() && !this.attempt.done() && !Objects.equals(this.attempt.timingHint(), this.displayedFault)) {
                           this.displayedFault = this.attempt.timingHint();
                           if (this.displayedFault != null) {
                              notifyPlayer(var4, this.displayedFault);
                           }
                        }

                        this.publish(this.attempt.done() ? this.attempt.progress() : "Training läuft");
                     }
                  } else if (!this.attempt.unscored() && (this.options.drill() != Drill.RETOTEM || this.attempt.done())) {
                     if (!this.attempt.done()) {
                        this.record(
                           !this.options.drill().series() || this.resetAfterPop && this.options.drill() == Drill.TOTEM_POPS,
                           this.resetAfterPop ? "Nach Pop neu aufstellen" : "Bot besiegt"
                        );
                     } else {
                        this.saveResult();
                     }

                     String var5 = this.attempt.progress();
                     if (!this.settings.autoRespawn() && !this.resetAfterPop) {
                        this.finish(var4, var5);
                     } else {
                        this.releaseBot();
                        this.respawnIn = 40;
                        this.publish(var5);
                        notifyPlayer(var4, var5 + " · Nächste Runde in 2 s");
                     }
                  } else {
                     this.finish(var4, this.attempt.unscored() ? this.attempt.progress() : "Training beendet. Der Bot ist nicht verfügbar.");
                  }
               } else {
                  if (this.options.drill() != Drill.RETOTEM) {
                     this.record(false, "Bot nicht mehr verfügbar");
                  }

                  this.finish(var4, "Training beendet. Der Bot ist nicht mehr verfügbar.");
               }
            }
         } else {
            if (var4 != null
               && var4.level() == this.arena
               && !var4.isAlive()
               && !this.waitingForStart
               && this.options.drill() == Drill.RETOTEM
               && this.attempt != null) {
               this.record(false, this.refillDeathReason());
            }

            this.finish(var4, "Training beendet.");
         }
      }
   }

   private void keepBotLoaded() {
      // The same short-lived ticket used for a moving pearl keeps the bot ticking while the player flies away.
      // It expires automatically 40 ticks after the last refresh and is never written to the world.
      this.arena.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, this.bot.chunkPosition(), 2);
   }

   boolean preventTrainingDeath(ServerPlayer var1, DamageSource var2) {
      if (var1 == this.owner && this.ownerId != null && TrainingArena.isArena(var1.level())) {
         var1.setHealth(Math.max(1.0F, Math.min(this.roundStartHealth, var1.getMaxHealth())));
         if (this.options.drill() == Drill.STUN_SLAM && (var2.is(DamageTypes.FALL)
               || var2.is(DamageTypes.STALAGMITE) || var2.is(DamageTypes.FLY_INTO_WALL))) {
            if (var2.is(DamageTypes.FLY_INTO_WALL) && !this.waitingForStart) this.attempt.slamWallImpact();
            return true;
         }
         if (this.options.drill() == Drill.RETOTEM && this.attempt != null) {
            if (this.attempt.done() || this.waitingForStart) {
               return true;
            }

            this.attempt.finish(false, this.refillDeathReason());
         }

         this.ownerDefeated = true;
         return true;
      } else {
         return false;
      }
   }

   private String refillDeathReason() {
      return "Tödlicher Treffer · " + this.attempt.refillProgress();
   }

   boolean owns(ServerPlayer var1) {
      return var1 == this.owner && this.ownerId != null && var1.level() == this.arena && this.attempt != null && this.bot != null;
   }

   boolean canPlaceCrystal(ServerPlayer var1) {
      return !this.waitingForStart
         && this.owns(var1)
         && var1.isAlive()
         && !var1.isSpectator()
         && this.bot.isAlive();
   }

   boolean placeCrystal(ServerPlayer var1, BlockPos var2) {
      if (this.canPlaceCrystal(var1) && this.tactics.placeCrystal(this.arena, this.bot, var1, var2)) {
         this.attempt.crystalPlaced(var2);
         return true;
      } else {
         return false;
      }
   }

   void playerHurt(ServerPlayer var1, DamageSource var2, float var3) {
      if (!this.waitingForStart && this.owns(var1)) {
         if (var2.is(DamageTypes.FLY_INTO_WALL)) {
            if (this.options.drill() == Drill.STUN_SLAM) this.attempt.slamWallImpact();
            if (secondarySlam != null) secondarySlam.slamWallImpact();
         }
         if (var2.getDirectEntity() instanceof TrainingCrystal) {
            this.ownCrystalLoss += var3;
         }

         if (var2.is(DamageTypes.BAD_RESPAWN_POINT)) {
            this.attempt.anchorSelfHit(var3);
         }

         if (var2.getEntity() == this.bot) {
            if (telemetry != null && var3 > 0) telemetry.recordDamage(arena.getServer().getTickCount(), TrainingTactics.withinReach(bot, var1, options.botReach()));
            this.attempt.playerHit(var1, var3);
         }
      }
   }

   private boolean comboWeapon(ServerPlayer var1) {
      ItemStack var2 = var1.getMainHandItem();
      return var2.is(ItemTags.SWORDS) || this.settings.mode() == TrainingSettings.Mode.CRYSTAL && var2.is(Items.TOTEM_OF_UNDYING);
   }

   void attackCharge(ServerPlayer var1, Entity var2, float var3) {
      this.directComboClick = -1;
      if (this.owns(var1)) {
         ++this.ownerAttackSequence;
         this.webClickOnBot = !this.waitingForStart && var2 == this.bot ? this.ownerAttackSequence : 0;
      }
      if (!this.waitingForStart && this.owns(var1) && var2 == this.bot) {
         this.swingCharge = var3;
         if (this.comboWeapon(var1)) {
            this.directComboClick = this.roundTick;
         }
      }
   }

   void playerAte(ServerPlayer var1, ItemStack var2) {
      if (!this.waitingForStart && this.owns(var1)) {
         this.attempt.ate(var1, var2);
      }
   }

   void playerPopped(ServerPlayer var1, ItemStack var2) {
      if (!this.waitingForStart && this.owns(var1) && !this.attempt.done()) {
         if (this.options.drill() == Drill.RETOTEM) {
            if (TrainingKit.usableTotems(var2) == 0) {
               return;
            }

            this.attempt.playerPop(TrainingKit.refillSupply(var1));
            notifyPlayer(var1, this.attempt.progress());
         } else {
            this.attempt.playerPop();
         }
      }
   }

   void playerJump(ServerPlayer var1) {
      if (!this.waitingForStart && this.owns(var1)) {
         if (webLandingTick >= 0) finishWebLanding();
         this.attempt.jump();
      }
   }

   void playerCrit(ServerPlayer var1, Entity var2) {
      if (!this.waitingForStart && this.owns(var1) && var2 == this.bot) {
         this.attempt.critical();
      }
   }

   void playerGlided(ServerPlayer var1) {
      if (!this.waitingForStart && this.owns(var1)) {
         this.attempt.glideStarted();
      }
   }

   void playerLaunched(ServerPlayer var1) {
      if (!this.waitingForStart && this.owns(var1)) {
         if (windHeight != null) windHeight.launched(var1, roundTick);
         if (options.drill() == Drill.LOW_WEB_SMASH) {
            if (webLandingTick >= 0) finishWebLanding();
            webWindPrimed = true;
            webWindAirborne = !var1.onGround();
            webLaunchTick = roundTick;
         }
         this.attempt.launch();
      }
   }

   boolean pearlCatchOnly(ServerPlayer player) {
      return owns(player) && options.pearlCatchOnly();
   }

   void pearlCatch(ServerPlayer var1) {
      if (!this.waitingForStart && this.owns(var1)) {
         this.attempt.catchPearl();
      }
   }

   void waterDrawn(ServerPlayer var1, BlockPos var2) {
      if (!this.waitingForStart && this.owns(var1)) {
         this.attempt.waterDrawn(var2, this.bot);
      }
   }

   void sawPlayerObsidian(ServerPlayer player, BlockPos pos) {
      if (waitingForStart || !owns(player)) return;
      if (options.drill() == Drill.OBI_TRAP) attempt.trapBlockPlaced(pos);
      if (options.drill()==Drill.DUEL && settings.mode()==TrainingSettings.Mode.CRYSTAL)
         tactics.crystalCover.added(pos,bot);
   }

   void webPlaced(ServerPlayer var1, BlockPos var2) {
      if (!this.waitingForStart && this.owns(var1)) {
         this.attempt.webPlaced(var2);
      }
   }

   void lavaPlaced(ServerPlayer var1, BlockPos var2) {
      if (!this.waitingForStart && this.owns(var1)) {
         this.attempt.lavaPlaced(var2);
      }
   }

   void anchorTriggered(ServerPlayer var1, BlockPos var2) {
      if (!this.waitingForStart && this.owns(var1)) {
         Vec3 var3 = Vec3.atCenterOf(var2);
         Vec3 var4 = var1.position().add(0.0, 0.2, 0.0);
         Vec3 var5 = var3.add(var4.subtract(var3).normalize().scale(0.9));
         boolean var6 = this.arena.clip(new ClipContext(var5, var4, Block.COLLIDER, Fluid.NONE, var1)).getType() == Type.BLOCK;
         this.attempt.anchor(var1, var2, var6);
      }
   }

   boolean defendingAgainst(TrainingCrystal var1) {
      return this.bot != null && this.attempt != null && this.options.drill().totemDefense() && var1.belongsTo(this.owner, this.bot);
   }

   int ownerPops() {
      return this.attempt == null ? 0 : this.attempt.playerPops();
   }

   boolean endDefenseBlast(boolean var1, boolean var2, int var3, boolean var4) {
      boolean var5 = this.ownerPops() > var3;
      this.attempt.defenseBlast(var1, var2, var5, this.ownCrystalLoss, !this.ownerDefeated && this.owner.isAlive(), var4);
      return var5 || this.ownCrystalLoss > 0.0F;
   }

   boolean beginCrystalBlast(TrainingCrystal var1) {
      if (this.bot != null && this.attempt != null && var1.belongsTo(this.owner, this.bot)) {
         this.lastBotDamage = this.ownCrystalLoss = 0.0F;
         this.popsBeforeCrystal = this.pops;
         this.targetInAirBeforeBlast = !this.bot.onGround() || !this.options.drill().requiresPopChain() && this.bot.getDeltaMovement().y > 0.02;
         this.hurtWindowRemaining = Math.max(0, this.bot.invulnerableTime - 10);
         return this.hurtWindowRemaining == 0;
      } else {
         return false;
      }
   }

   void endCrystalBlast(TrainingCrystal var1, Vec3 var2, Vec3 var3, float var4, boolean var5) {
      if (this.bot != null && this.attempt != null && var1.belongsTo(this.owner, this.bot)) {
         this.attempt
            .crystalBlast(
               this.owner,
               this.bot,
               var2,
               var3,
               this.lastBotDamage,
               Math.max(var4, this.ownCrystalLoss),
               var5,
               this.targetInAirBeforeBlast,
               this.hurtWindowRemaining,
               this.pops > this.popsBeforeCrystal
            );
      }
   }

   void stop(MinecraftServer var1) {
      if (this.ownerId != null) {
         this.recordInterruption("Versuch beendet");
         this.finish(var1.getPlayerList().getPlayer(this.ownerId), "Training beendet.");
      }
      ArenaRound.stopIdle(var1);
   }

   void cleanup(MinecraftServer var1) {
      this.finish(this.ownerId == null ? null : var1.getPlayerList().getPlayer(this.ownerId), "");
      this.snapshot = TrainingStatus.idle();
   }

   private boolean airStartLanded() {
      return this.suspendedAt != null && !this.waitingForStart
         && this.owner.onGround() && this.owner.getY() < this.suspendedAt.y - 0.5;
   }

   boolean holding(Entity var1) {
      return this.waitingForStart && (var1 == this.owner || var1 == this.bot && options.drill() != Drill.WIND_STUN_SLAM);
   }

   void releaseStart(ServerPlayer var1, long var2) {
      if (this.waitingForStart && var2 != 0L && var2 == this.startToken && var1 == this.owner && this.owns(var1) && var1.isAlive()) {
         RoundPing.start(var1, this.bot, this.options);
         var clock = this.arena.getServer().tickRateManager();
         float requested = this.options.tickRate();
         if (requested != clock.tickrate()) {
            this.priorTickRate = clock.tickrate();
            this.appliedTickRate = requested;
            clock.setTickRate(requested);
         }
         this.waitingForStart = false;
         ArenaRound.started(var1, this.bot, this.settings);
         if (var1.hasEffect(net.minecraft.world.effect.MobEffects.ABSORPTION)) {
            // Arena setup clears the previous world's golden hearts after equipping the player's kit.
            var1.setAbsorptionAmount(var1.getMaxAbsorption());
         }
         this.startToken = 0L;
         if (!TrainingArena.airStart(this.options)) {
            this.suspendedAt = null;
         }

         var1.setNoGravity(false);
         var1.setDeltaMovement(Vec3.ZERO);
         var1.resetFallDistance();
         if (this.suspendedAt != null) {
            var1.setOnGround(false);
         }

         if (options.drill().centeredMaceDrill() && suspendedAt != null) {
            if (options.drill() == Drill.LOW_WEB_SMASH) attempt.startWebDrop();
            suspendedAt = null;
         }
         var1.hurtMarked = true;
         DrillOptions.SlamScene var4 = this.options.slamScene();
         this.bot.setNoGravity(options.drill() != Drill.WIND_STUN_SLAM && (var4 == DrillOptions.SlamScene.GROUND_LOCK || var4 == DrillOptions.SlamScene.AIR_LOCK
            || maceCenter != null && var4 != DrillOptions.SlamScene.PAIRED_FALL));
         this.bot.resetFallDistance();
         if (var4 == DrillOptions.SlamScene.PAIRED_FALL && options.drill() != Drill.WIND_STUN_SLAM) {
            this.bot.setOnGround(false);
         }

         if (options.drill() == Drill.WIND_STUN_SLAM) previousRepetitionAt = secondaryPopAt = roundTick;
         else roundTick = 0;
         if (this.options.drill() == Drill.STUN_SLAM) this.attempt.observeSlamLanding(var1);
         if (BotTelemetry.eligible(settings, options)) {
            int tick = arena.getServer().getTickCount();
            telemetry = new BotTelemetry(tick);
            telemetry.observe(tick, TrainingTactics.withinReach(bot, var1, options.botReach()));
         }
         this.publish("Training läuft");
         String rank = this.options.drill() == Drill.DUEL ? " · " + this.settings.duelRank() : "";
         notifyPlayer(var1, this.options.drill().label() + rank + " · Runde " + this.round);
      }
   }

   private static void startPrompt(ServerPlayer var0) {
      ArenaRound.prompt(var0);
   }

   private void steer(ServerPlayer var1) {
      if (this.settings.mode() == TrainingSettings.Mode.SWORD && this.options.drill() == Drill.DUEL && this.settings.counterAttacks()) {
         this.tactics.swordDuel.steer(this.bot, var1, this.settings, this.options, this.roundTick);
         return;
      }
      if (duelFootwork != null) {
         duelFootwork.steer(bot, var1, movementSettings, options, tactics, roundTick);
         return;
      }
      float var2 = this.bot.getYRot();
      float var3 = this.bot.getXRot();
      if (this.options.gaze() == DrillOptions.Gaze.TRACK) {
         this.bot.lookAt(Anchor.EYES, RoundPing.eyes(var1));
         this.bot.setYRot(Mth.approachDegrees(var2, this.bot.getYRot(), this.options.turnRate()));
         this.bot.setXRot(Mth.approachDegrees(var3, this.bot.getXRot(), this.options.turnRate()));
      }

      this.bot.setYHeadRot(this.bot.getYRot());
      this.bot.setYBodyRot(this.bot.getYRot());
      Vec3 var4 = this.options.drill().totemDefense() ? this.tactics.pursuitPoint(var1) : RoundPing.position(var1);
      Vec3 var5 = var4.subtract(this.bot.position());
      double var6 = var5.horizontalDistance();
      double var8 = this.options.drill().totemDefense()
         ? 1.3
         : 2.1;
      var8 = Math.min(var8, options.botReach() - .2);
      double var10 = var6 > var8 + 0.25 ? 1 : 0;
      double var12 = 0.0;
      if (this.settings.movement() == TrainingSettings.Movement.STRAFE && var6 < var8 + 3.0) {
         var12 = this.roundTick / 34 % 2 == 0 ? 0.8 : -0.8;
         if (var6 < var8 - 0.4) {
            var10 = -0.6;
         }
      }

      double var14 = Math.atan2(-var5.x, var5.z) - Math.toRadians(this.bot.getYRot());
      boolean var16 = options.drill() != Drill.PERFECT_WIND_CHARGE && maceCenter == null && this.options.slamScene() == DrillOptions.SlamScene.FREE
         && (this.settings.movement() != TrainingSettings.Movement.STILL || this.options.drill().totemDefense());
      this.bot.zza = var16 ? (float)(var10 * Math.cos(var14) + var12 * Math.sin(var14)) : 0.0F;
      this.bot.xxa = var16 ? (float)(-var10 * Math.sin(var14) + var12 * Math.cos(var14)) : 0.0F;
      if (this.options.drill().totemDefense()) this.bot.setSprinting(var16 && var10 > 0 && !this.bot.isUsingItem());
      this.bot.setJumping(var16 && this.bot.horizontalCollision && Math.abs(this.bot.zza) + Math.abs(this.bot.xxa) > 0.1);
   }

   private void refreshSeries() {
      List<DrillHistory.Result> var1 = this.history.get(this.historyKey);
      this.recentResults = List.copyOf(var1);
      int[] var2 = var1.stream().filter(DrillHistory.Result::won).mapToInt(DrillHistory.Result::ticks).sorted().toArray();
      this.wins = var2.length;
      this.attempts = var1.size();
      this.median = this.wins == 0 ? -1 : (var2[(this.wins - 1) / 2] + var2[this.wins / 2]) / 2;
   }

   private void recordRepetition() {
      TrainingProgress.recordRound(
         this.history,
         this.historyKey,
         new DrillHistory.Result(true, this.roundTick - this.previousRepetitionAt, -1.0, this.attempt.progress(), this.attempt.timeline()),
         this.settings,
         this.options,
         this.ownerId,
         this,
         this.roundSequence
      );
      this.previousRepetitionAt = this.roundTick;
      this.refreshSeries();
   }

   private void record(boolean var1, String var2) {
      if (TrainingProgress.freeOutcome(
         this,
         this.history,
         this.historyKey,
         this.settings,
         this.options,
         this.ownerId,
         this.roundSequence,
         this.roundTick,
         this.waitingForStart,
         this.ownerDefeated,
         this.resetAfterPop,
         this.bot,
         this.attempt
      )) {
         this.refreshSeries();
      }
      if (!waitingForStart && (ownerDefeated || bot != null && bot.isDeadOrDying() && !resetAfterPop)) {
         TrainingProgress.technique(historyKey, settings, options, ownerId, Drill.TOTEM_POPS, false,
            Math.max(0, roundTick - secondaryPopAt), ownerDefeated ? "Spieler besiegt" : "Bot ohne Totem besiegt");
      }
      if (this.attempt != null) {
         if (!this.attempt.done()) {
            if (this.options.drill() == Drill.STUN_SLAM) return;
            this.attempt.finish(var1, var2);
         }

         this.saveResult();
      }
   }

   private void recordInterruption(String var1) {
      if (this.attempt != null && !this.saved) {
         if (!waitingForStart && bot != null && options.drill() == Drill.OBI_TRAP && !attempt.done())
            attempt.checkObsidianTrap(bot.level(), bot.blockPosition());
         if (this.attempt.done()) {
            this.saveResult();
         }
         // A voluntary restart or stop is neutral. Keep the sibling saveResult path for a finished attempt.
      }
   }

   void observeWebGround(boolean grounded) {
      if (options == null || options.drill() != Drill.LOW_WEB_SMASH || waitingForStart || owner == null || attempt == null) return;
      boolean supported = grounded && webFeetSupported();
      int settleTicks = 3 + (int)Math.ceil(options.playerPing() * options.tickRate() / 1000.0);
      if (webLandingTick >= 0) {
         // A near-floor smash can send a ground packet before the Wind Burst motion arrives.
         if (!supported && webChainActive && webLastSmashTick >= webLandingTick - settleTicks
               && owner.getY() > webLandingY + .02) webLandingTick = -1;
         else if (roundTick - webLandingTick >= settleTicks) finishWebLanding();
      }
      if (!supported) {
         if (webWindPrimed) webWindAirborne = true;
         if (webWindAirborne || owner.fallDistance > 1.5 && owner.getMainHandItem().is(Items.MACE)) webFlightAttempted = true;
      } else if (webLandingTick < 0 && (webFlightAttempted || webFlightJudged
            || webWindPrimed && (webWindAirborne || roundTick - webLaunchTick > 8))) {
         webLandingTick = roundTick;
         webLandingY = owner.getY();
      }
   }

   private boolean webFeetSupported() {
      var box = owner.getBoundingBox();
      var feet = new net.minecraft.world.phys.AABB(box.minX + .0001, box.minY - .002, box.minZ + .0001,
         box.maxX - .0001, box.minY, box.maxZ - .0001);
      return owner.level().getBlockCollisions(owner, feet).iterator().hasNext();
   }

   private void finishWebLanding() {
      if (webFlightAttempted && !webFlightJudged && !attempt.done()) {
         attempt.finish(false, "Ohne Smash gelandet");
         storeMaceRepetition();
      }
      endWebChain();
   }

   void endWebChain() {
      webLandingTick = -1;
      webLastSmashTick = -1000;
      webWindPrimed = webWindAirborne = webFlightAttempted = webFlightJudged = false;
      if (!webChainActive) return;
      TrainingProgress.endAirChain(ownerId, historyKey);
      webChainActive = false;
   }

   private void storeMaceRepetition() {
      var result = new DrillHistory.Result(attempt.won(), roundTick - previousRepetitionAt, attempt.control(), attempt.progress(), attempt.timeline());
      TrainingProgress.record(history, historyKey, result, settings, options, ownerId);
      if (options.drill() == Drill.LOW_WEB_SMASH) {
         webFlightJudged = true;
         if (result.won()) {
            webChainActive = true;
            webLastSmashTick = roundTick;
            webLandingTick = -1;
         }
         owner.sendOverlayMessage(Component.literal(result.won() ? "Geschafft" : "Fehlversuch · " + result.reason())
            .withStyle(result.won() ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED));
      }
      previousRepetitionAt = roundTick;
      if (options.drill() == Drill.WIND_STUN_SLAM) scoredWindFlight = true;
      attempt = new DrillAttempt(options, -1, round - 1);
      airborneAxeStun = false;
      refreshSeries();
   }

   private boolean windTargetAirborne() {
      double base = windPlatform == null ? windBaseY : windPlatform.getY() + 1;
      return !bot.onGround() && bot.getY() > base + 0.15;
   }

   private void tickWindTarget() {
      if (!bot.onGround()) nextWindLaunch = roundTick + 12;
      windCatch.tick();
      if (waitingForWindApex) {
         if (!bot.onGround()) {
            windLiftAirborne = true;
            // Two ticks to switch items. Throw only once the upward movement actually ends.
            if (bot.getDeltaMovement().y <= .16) {
               waitingForWindApex = false;
               windEquipShieldAt = 0;
               windThrow = WindThrow.PEARL;
               equipWindThrow();
               return;
            }
         } else if (windLiftAirborne || roundTick >= nextWindLaunch) {
            waitingForWindApex = false;
         }
      }
      if (windEquipAt != 0) {
         if (roundTick >= windEquipAt) {
            windEquipAt = 0;
            equipWindThrow();
         }
         return;
      }
      if (windEquipShieldAt != 0) {
         if (roundTick >= windEquipShieldAt) {
            equipment.equipWeapon(bot, Items.SHIELD);
            // Let equipment tracking reach the client before sending the using-item flag.
            windGuardAt = roundTick + 3;
            windEquipShieldAt = 0;
         }
         return;
      }
      if (windThrowAt != 0) {
         if (roundTick < windThrowAt) return;
         windThrowAt = 0;
         switch (windThrow) {
            case LIFT -> {
               if (bot.onGround() && throwWindCharge() && options.windFlight() == DrillOptions.WindFlight.CHARGE_AND_CATCH) {
                  waitingForWindApex = true;
                  windLiftAirborne = false;
               }
            }
            case PEARL -> {
               boolean combined = options.windFlight() == DrillOptions.WindFlight.CHARGE_AND_CATCH;
               if (combined && !bot.onGround() && bot.getDeltaMovement().y > 0) {
                  windThrowAt = roundTick + 1;
                  return;
               }
               if ((!combined || !bot.onGround()) && windCatch.throwPearl(arena, bot, equipment)) {
                  if (!combined) scoredWindFlight = false;
                  windThrow = WindThrow.CATCH;
                  int throwDelay = combined ? MIN_APEX_CATCH_DELAY : 4;
                  windEquipAt = roundTick + throwDelay - WIND_ITEM_SWITCH_TICKS;
                  return;
               }
            }
            case CATCH -> {
               if (!windCatch.throwCharge(arena, bot, equipment, options.windFlight() == DrillOptions.WindFlight.CHARGE_AND_CATCH)) {
                  windThrowAt = roundTick + 1;
                  return;
               }
            }
         }
         windEquipShieldAt = roundTick + 3;
         return;
      }
      if (!bot.onGround() || windCatch.inFlight() || waitingForWindApex) return;
      if (airborneAxeStun) {
         attempt.finish(false, "Der Bot ist nach deinem Axt-Stun vor dem Smash gelandet.");
         storeMaceRepetition();
      }
      if (!waitingForStart && options.slamScene() == DrillOptions.SlamScene.FREE && settings.counterAttacks()
            && roundTick == nextWindLaunch - 5 && TrainingTactics.withinReach(bot, owner) && equipment.has(bot, Items.MACE)) {
         bot.stopUsingItem();
         equipment.equipWeapon(bot, Items.MACE);
         TrainingTactics.strike(arena, bot, owner, bot.damageSources().mobAttack(bot), 0);
         windEquipShieldAt = roundTick + 2;
         return;
      }
      if (roundTick < nextWindLaunch) return;
      nextWindLaunch = roundTick + 12;
      if (!equipment.has(bot, Items.WIND_CHARGE) || !equipment.has(bot, Items.SHIELD)) {
         attempt.defenseUnavailable("Bot-Kit aufgebraucht · Windladung und Schild werden benötigt.");
         return;
      }
      windThrow = options.windFlight() == DrillOptions.WindFlight.CATCH ? WindThrow.PEARL : WindThrow.LIFT;
      equipWindThrow();
   }

   private void equipWindThrow() {
      var item = windThrow == WindThrow.PEARL ? Items.ENDER_PEARL : Items.WIND_CHARGE;
      bot.stopUsingItem();
      if (equipment.equipWeapon(bot, item)) windThrowAt = roundTick + WIND_ITEM_SWITCH_TICKS;
      else {
         attempt.defenseUnavailable("Bot-Kit aufgebraucht · " + (item == Items.ENDER_PEARL ? "Enderperle fehlt." : "Windladung fehlt."));
         windEquipShieldAt = roundTick + 1;
      }
   }

   private boolean throwWindCharge() {
      var charge = new WindCharge(arena, bot.getX(), bot.getY() + 0.5, bot.getZ(), new Vec3(0, -1, 0));
      charge.setOwner(bot);
      charge.shoot(0, -1, 0, 1.5F, 0);
      if (arena.addFreshEntity(charge)) {
         targetCharge = charge;
         // A new flight resets scoring. The shield still observes its vanilla axe cooldown.
         scoredWindFlight = false;
         bot.jumpFromGround();
         bot.swing(InteractionHand.MAIN_HAND, true);
         BotTelemetry.action(arena, bot, "WIND");
         equipment.takeOne(bot, Items.WIND_CHARGE);
         return true;
      }
      return false;
   }

   private void armSecondarySlam(int window) {
      secondarySlam = new DrillAttempt(DrillOptions.forDrill(Drill.STUN_SLAM), -1, 0);
      secondarySlamAt = roundTick;
      secondarySlam.observeSecondarySlam(roundTick, owner);
      secondarySlam.shieldStun(window);
   }

   private void finishSecondarySlam() {
      TrainingProgress.technique(historyKey, settings, options, ownerId, Drill.STUN_SLAM,
         secondarySlam.won(), roundTick - secondarySlamAt, secondarySlam.progress());
      secondarySlam = null;
   }

   private void saveResult() {
      if (!this.saved && this.attempt != null && this.attempt.done() && !this.attempt.unscored() && this.options.drill().series()) {
         this.saved = true;
         TrainingProgress.recordRound(
            this.history,
            this.historyKey,
            new DrillHistory.Result(this.attempt.won(), this.attempt.duration(), this.attempt.control(), this.attempt.progress(), this.attempt.timeline()),
            this.settings,
            this.options,
            this.ownerId,
            this,
            this.roundSequence
         );
         this.refreshSeries();
      }
   }

   private void publish(String var1) {
      this.snapshot = new TrainingStatus(
         this.ownerId != null,
         var1,
         this.round,
         this.hits,
         this.bot == null ? 0.0F : this.bot.getHealth(),
         this.settings == null ? 0 : this.settings.maxHealth(),
         this.pops,
         this.wins,
         this.attempts,
         this.median,
         this.attempt == null ? "" : this.attempt.progress(),
         this.attempt == null ? List.of() : this.attempt.timeline(),
         this.recentResults,
         this.settings == null ? null : this.settings.mode(),
         this.options == null ? null : this.options.drill(),
         this.historyKey == null ? "" : this.historyKey,
         this.waitingForStart ? this.startToken : 0L,
         ownerId != null && options != null && options.drill() == Drill.OBI_TRAP && attempt != null
            ? new TrainingStatus.ObiTimer(attempt.time(), options.tickRate(), obiBestSeconds,
               !waitingForStart && (!attempt.done() || attempt.won())) : null,
         windHeight == null ? 0 : windHeight.meters()
      );
   }

   void observeBotAction(LivingEntity actor, String kind) {
      if (telemetry != null && actor == bot) telemetry.recordAction(arena.getServer().getTickCount(), kind);
   }

   BotTelemetry.Snapshot botTelemetry() {
      return telemetry == null ? null : telemetry.snapshot(arena.getServer().getTickCount());
   }

   private void releaseBot() {
      RoundPing.end();

      if (owner != null && options != null && options.pearlCatchOnly()) {
         for (var pearl : List.copyOf(owner.getEnderPearls())) {
            if (pearl.level() == arena) pearl.discard();
         }
      }
      if (this.appliedTickRate > 0) {
         var clock = this.arena.getServer().tickRateManager();
         if (clock.tickrate() == this.appliedTickRate) clock.setTickRate(this.priorTickRate);
         this.appliedTickRate = 0;
      }
      if (telemetry != null) {
         TrainingProgress.botRoundClosed(this, historyKey, settings, options, ownerId, roundSequence, botTelemetry());
         telemetry = null;
      }
      if (this.tactics != null) {
         this.tactics.clear();
         this.tactics = null;
         this.duelFootwork = null;
      }
      if (maceCenter != null && arena != null && options.drill() == Drill.LOW_WEB_SMASH) {
         BlockPos web = BlockPos.containing(maceCenter).above();
         if (arena.getBlockState(web).is(Blocks.COBWEB)) arena.removeBlock(web, false);
      }
      if (targetCharge != null) targetCharge.discard();
      targetCharge = null;
      windCatch.clear();
      windEquipAt = 0;
      waitingForWindApex = windLiftAirborne = false;
      nextWindLaunch = 1;
      windHeight = null;
      if (windPlatform != null && arena != null && arena.getBlockState(windPlatform).is(Blocks.POLISHED_BLACKSTONE_BRICKS)) arena.removeBlock(windPlatform, false);
      windPlatform = null;
      windThrowAt = windEquipShieldAt = windGuardAt = 0;
      endWebChain();
      airborneAxeStun = false;
      scoredWindFlight = false;
      maceCenter = null;
      movementSettings = null;

      if (this.bot != null && !this.bot.isRemoved()) {
         this.bot.discard();
      }

      this.bot = null;
      this.equipment = null;
   }

   private void finish(ServerPlayer var1, String var2) {
      TrainingResources.end(this.ownerId);
      var1 = TrainingArena.currentPlayer(var1);
      if (this.attempt != null && this.options.drill() == Drill.RETOTEM) {
         this.saveResult();
      }

      this.releaseBot();
      if (this.waitingForStart && var1 != null && TrainingArena.isArena(var1.level())) {
         var1.setNoGravity(false);
      }

      this.waitingForStart = false;
      this.suspendedAt = null;
      if (var1 != null && this.ownerId != null) {
         try {
            TrainingArena.leave(var1);
            var1 = TrainingArena.currentPlayer(var1);
         } catch (RuntimeException var4) {
            LoggerFactory.getLogger("pvptrainer").error("Arena-Rückkehr fehlgeschlagen", var4);
            var2 = "Rückkehr offen: " + var4.getMessage();
         }
      }

      this.ownerId = null;
      this.publish(var2);
      if (var1 != null && !var2.isEmpty()) {
         notifyPlayer(var1, var2);
      }

      this.arena = null;
      this.owner = null;
      this.settings = null;
      this.options = null;
      this.attempt = null;
      this.ownerDefeated = this.resetAfterPop = false;
      this.respawnIn = 0;
   }

   private static void notifyPlayer(ServerPlayer var0, String var1) {
      var0.sendOverlayMessage(Component.literal(var1));
   }

   private record RoundSelection(TrainingSettings settings, DrillOptions options, KitLoadout playerKit, KitLoadout botKit) {
   }

   private record Rules(String difficulty, boolean regeneration) {
      String key(TrainingSettings.Mode var1) {
         return this.difficulty + "|natregen=" + (var1 != TrainingSettings.Mode.UHC && this.regeneration);
      }
   }

   private final class TrainingBot extends Mannequin implements MaceDuel.Glider, TrainingTactics.MeleeReach {
      private int reconsiderReachAt = -1;
      private double chosenReach;
      public double meleeReach() {
         int tick = TrainingSession.this.roundTick;
         if (tick >= reconsiderReachAt) {
            double cap = TrainingSession.this.options.botReach();
            // Commit to one distance for several ticks instead of rerolling every range check.
            chosenReach = cap - getRandom().nextDouble() * Math.min(.32, cap * .12);
            reconsiderReachAt = tick + 7 + getRandom().nextInt(6);
         }
         return chosenReach;
      }

      public boolean launchGlide() {
         if (onGround() || !canGlide()) return false;
         setSharedFlag(FLAG_FALL_FLYING, true);
         return true;
      }
      private float damageThisHit;
      private int immediateGuardUntil = -1;
      private int stunImpulseAt = -1;
      private long pendingWebClick;
      private int pendingWebTick, pendingWebDuration;

      private void expireWebBreak() {
         if (pendingWebClick != 0 && roundTick - pendingWebTick > 4) {
            pendingWebClick = 0;
            attempt.finish(false, "Schild ohne Doppelklick gebrochen");
         }
      }
      private DamageSource blockedMelee;
      private int blockedMeleeTick = -1;

      TrainingBot(ServerLevel var2) {
         super(var2);
         this.applyImplicitComponent(DataComponents.PROFILE, MacherAppearance.PROFILE);
         this.setCustomName(MacherAppearance.NAME);
         this.setCustomNameVisible(true);
      }

      public void startSeenByPlayer(ServerPlayer var1) {
         super.startSeenByPlayer(var1);
         if (this.isUsingItem() && this.getUseItem().has(DataComponents.BLOCKS_ATTACKS)) {
            byte var2 = (Byte)this.getEntityData().get(DATA_LIVING_ENTITY_FLAGS);
            this.sendUseFlags(var1, (byte)(var2 & -2));
            this.sendUseFlags(var1, var2);
         }
      }

      private void sendUseFlags(ServerPlayer var1, byte var2) {
         var1.connection.send(new ClientboundSetEntityDataPacket(this.getId(), List.of(DataValue.create(DATA_LIVING_ENTITY_FLAGS, var2))));
      }

      private Vec3 constrainedMotion(Vec3 var1) {
         if (options != null && options.drill() == Drill.WIND_STUN_SLAM) return maceCenter == null ? var1 : new Vec3(0, var1.y, 0);
         if (TrainingSession.this.waitingForStart || maceCenter != null && options.slamScene() != DrillOptions.SlamScene.PAIRED_FALL) {
            return Vec3.ZERO;
         } else if (TrainingSession.this.options == null || TrainingSession.this.options.slamScene() == DrillOptions.SlamScene.FREE) {
            return var1;
         } else {
            return TrainingSession.this.options.slamScene() == DrillOptions.SlamScene.PAIRED_FALL && !TrainingSession.this.waitingForStart
               ? new Vec3(0.0, Math.min(0.0, var1.y), 0.0)
               : Vec3.ZERO;
         }
      }

      public void move(MoverType var1, Vec3 var2) {
         super.move(var1, this.constrainedMotion(var2));
         if (maceCenter != null && options.drill() != Drill.WIND_STUN_SLAM && options.slamScene() != DrillOptions.SlamScene.PAIRED_FALL) {
            // A fixed bot never descends into the floor, so ordinary movement cannot detect the ground contact.
            setOnGround(!level().noBlockCollision(this, getBoundingBox().move(0, -.01, 0)));
         }
      }

      public void setDeltaMovement(Vec3 var1) {
         super.setDeltaMovement(this.constrainedMotion(var1));
      }

      @Override
      public void knockback(double strength, double x, double z, DamageSource source, float damage, boolean force) {
         boolean webMelee = TrainingSession.this.options != null && TrainingSession.this.options.drill() == Drill.STUN_WEB
            && source != null && source.is(DamageTypes.PLAYER_ATTACK) && source.getDirectEntity() == TrainingSession.this.owner;
         if (webMelee && this.stunImpulseAt == TrainingSession.this.roundTick) return;
         boolean blocked = source != null && source == this.blockedMelee && this.blockedMeleeTick == TrainingSession.this.roundTick;
         boolean keepGrounded = (blocked || webMelee) && this.onGround();
         double previousY = this.getDeltaMovement().y;
         super.knockback(strength, x, z, source, damage, force);
         if (keepGrounded) {
            Vec3 after = this.getDeltaMovement();
            this.setDeltaMovement(new Vec3(after.x, Math.min(previousY, after.y), after.z));
         }
      }

      private boolean refillTarget() {
         return TrainingSession.this.options != null && TrainingSession.this.options.drill().totemDefense() && TrainingSession.this.refillRecoverySpot != null;
      }

      private void recoverRefillTarget() {
         this.snapTo(TrainingSession.this.refillRecoverySpot, this.getYRot(), 0.0F);
         this.setDeltaMovement(Vec3.ZERO);
         this.resetFallDistance();
         this.clearFire();
         this.setHealth(this.getMaxHealth());
         this.hurtMarked = true;
      }

      protected void onBelowWorld() {
         if (this.refillTarget()) {
            this.recoverRefillTarget();
         } else {
            super.onBelowWorld();
         }
      }

      public boolean hurtServer(ServerLevel var1, DamageSource var2, float var3) {
         if (TrainingSession.this.waitingForStart) {
            return false;
         }

         TrainingSession.this.equipment.protectPassiveTarget(this);
         ItemStack var4 = this.getMainHandItem();
         ItemStack var5 = this.getOffhandItem();
         int var6 = var4.getCount();
         int var7 = var5.getCount();
         boolean var8 = var4.has(DataComponents.DEATH_PROTECTION);
         boolean var9 = var5.has(DataComponents.DEATH_PROTECTION);
         boolean var10 = this.isBlocking();
         BlocksAttacks var11 = (BlocksAttacks)this.getUseItem().get(DataComponents.BLOCKS_ATTACKS);
         int var12 = this.isUsingItem() && var11 != null ? var11.blockDelayTicks() - this.getTicksUsingItem() : 0;
         boolean var13 = !this.onGround() || this.getDeltaMovement().y > 0.02;
         boolean var14 = this.invulnerableTime <= 10;
         boolean confirmWeb = pendingWebClick != 0 && roundTick - pendingWebTick <= 4
               && var2.is(DamageTypes.PLAYER_ATTACK) && var2.getDirectEntity() == owner
               && owner.getMainHandItem().is(ItemTags.AXES) && webClickOnBot == pendingWebClick + 1;
         boolean drillSmash = options.drill().centeredMaceDrill() && var2.is(DamageTypes.MACE_SMASH)
               && var2.getDirectEntity() == owner && owner.getMainHandItem().is(Items.MACE);
         boolean validSmash = drillSmash && (options.drill() == Drill.LOW_WEB_SMASH
               || windTargetAirborne() && airborneAxeStun && attempt.shieldWindowOpen() && !var10);
         boolean webContact = options.drill() == Drill.LOW_WEB_SMASH && var2.getDirectEntity() == owner
               && owner.getMainHandItem().is(Items.MACE) && (drillSmash || var2.is(DamageTypes.PLAYER_ATTACK));
         // Test before damage, totem handling or Wind Burst changes the attacker's fall state.
         boolean webSmash = webContact && (drillSmash || MaceItem.canSmashAttack(owner));
         if (webSmash && invulnerableTime > 10) invulnerableTime = 10;
         boolean var15 = super.hurtServer(var1, var2, var3);
         if (webContact) {
            if (webSmash || !webFlightJudged) {
               attempt.finish(webSmash, webSmash ? "Smash unter der Netzkante gelandet!" : "Zu wenig Fallhöhe");
               storeMaceRepetition();
            }
         } else if (drillSmash && var15 && !attempt.done() && !scoredWindFlight) {
            attempt.finish(validSmash, validSmash ? "Axt-Stun und Smash im selben Flug!" : "Brich das Schild in der Luft und triff den Bot vor seiner Landung.");
            storeMaceRepetition();
         }
         if (confirmWeb && !attempt.done()) {
            pendingWebClick = 0;
            attempt.shieldStun(Math.max(1,pendingWebDuration - (roundTick - pendingWebTick)));
            armSecondarySlam(Math.max(1,pendingWebDuration - (roundTick - pendingWebTick)));
            stunImpulseAt = roundTick;
            if (onGround()) super.knockback(.4, owner.getX()-getX(),owner.getZ()-getZ(),var2,var3,false);
         }
         if (TrainingSession.this.attempt != null && TrainingSession.this.owner != null) {
            if (TrainingSession.this.options.drill() == Drill.STUN_SLAM && var2.getEntity() == TrainingSession.this.owner) {
               if (TrainingSession.this.owner.getMainHandItem().is(ItemTags.AXES)) {
                  TrainingSession.this.attempt.axeContact(var10, var12);
               }

               if (TrainingSession.this.owner.getMainHandItem().is(Items.MACE)
                  && (!var15 || !var2.is(DamageTypes.MACE_SMASH))) {
                  TrainingSession.this.attempt
                     .maceContact(
                        TrainingSession.this.owner.fallDistance,
                        TrainingSession.this.owner.onGround(),
                        var2.is(DamageTypes.MACE_SMASH)
                     );
               }
            }

            if (var15 && var2.getEntity() == TrainingSession.this.owner) {
               TrainingSession.this.hits++;
               if (secondarySlam != null && var2.is(DamageTypes.MACE_SMASH) && owner.getMainHandItem().is(Items.MACE)) {
                  secondarySlam.smash(owner, var10);
                  if (secondarySlam.won()) finishSecondarySlam();
               }
               boolean stunSlam = TrainingSession.this.options.drill() == Drill.STUN_SLAM;
               if (!drillSmash && var2.is(DamageTypes.MACE_SMASH) && (stunSlam ? TrainingSession.this.owner.getMainHandItem().is(Items.MACE)
                     : !TrainingSession.this.airStartLanded())) {
                  TrainingSession.this.attempt.smash(TrainingSession.this.owner, var10);
                  if (stunSlam && TrainingSession.this.attempt.won()) TrainingSession.this.saveResult();
               }
            }

            if (var2.is(DamageTypes.PLAYER_ATTACK)
               && var2.getDirectEntity() == TrainingSession.this.owner
               && TrainingSession.this.comboWeapon(TrainingSession.this.owner)
               && (
                  var15
                     || TrainingSession.this.directComboClick == TrainingSession.this.roundTick
                        && !var14
                        && !var10
                        && var3 > 0.0F
                        && TrainingSession.this.attempt.needsAirSword()
               )) {
               TrainingSession.this.attempt
                  .swordContact(TrainingSession.this.owner, this, var15 ? this.damageThisHit : 0.0F, TrainingSession.this.swingCharge, var13, var15, var14);
               TrainingSession.this.directComboClick = -1;
            }

            InteractionHand var16 = var8 && var4.getCount() < var6
               ? InteractionHand.MAIN_HAND
               : (var9 && var5.getCount() < var7 ? InteractionHand.OFF_HAND : null);
            if (var16 != null && this.isAlive()) {
               TrainingSession.this.pops++;
               TrainingSession.this.attempt.botPop();
               if (TrainingSession.this.options.drill() == Drill.TOTEM_POPS) {
                  TrainingSession.this.recordRepetition();
               } else {
                  TrainingProgress.technique(historyKey, settings, options, ownerId, Drill.TOTEM_POPS, true,
                     roundTick - secondaryPopAt, "Bot-Totem ausgelöst");
                  secondaryPopAt = roundTick;
               }

               TrainingSession.this.equipment.popped(this, var16, TrainingSession.this.roundTick);
               TrainingSession.this.resetAfterPop = TrainingSession.this.options.resetOnPop();
            }

            return var15;
         } else {
            return var15;
         }
      }

      public void startUsingItem(InteractionHand var1) {
         if (TrainingSession.this.roundTick >= TrainingSession.this.shieldReadyAt || !this.getItemInHand(var1).has(DataComponents.BLOCKS_ATTACKS)) {
            super.startUsingItem(var1);
         }
      }

      private void pauseShield(int ticks, BlocksAttacks shield, boolean exactWindow) {
         int readyAt = TrainingSession.this.roundTick + ticks;
         TrainingSession.this.shieldReadyAt = readyAt;
         if (exactWindow) {
            // Count until coverage resumes, including the usual shield-raise animation.
            this.immediateGuardUntil = readyAt + shield.blockDelayTicks();
            if (ticks == 0) this.startUsingItem(TrainingSession.this.equipment.shieldHand);
         }
      }

      public ItemStack getItemBlockingWith() {
         return TrainingSession.this.options != null
               && TrainingSession.this.roundTick <= this.immediateGuardUntil
               && this.isUsingItem()
               && this.getUseItem().has(DataComponents.BLOCKS_ATTACKS)
            ? this.getUseItem()
            : super.getItemBlockingWith();
      }

      protected void actuallyHurt(ServerLevel var1, DamageSource var2, float var3) {
         float var4 = DrillAttempt.health(this);
         super.actuallyHurt(var1, var2, var3);
         this.damageThisHit = Math.max(0.0F, var4 - DrillAttempt.health(this));
         // Keep the training target available even in kits without death protection.
         if (options.drill().centeredMaceDrill() && getHealth() <= 0
               && !getMainHandItem().has(DataComponents.DEATH_PROTECTION) && !getOffhandItem().has(DataComponents.DEATH_PROTECTION)) {
            setHealth(getMaxHealth());
         }
         if (damageThisHit > 0 && TrainingSession.this.options.drill() == Drill.DUEL
               && TrainingSession.this.settings.mode() == TrainingSettings.Mode.SWORD
               && var2.is(DamageTypes.PLAYER_ATTACK) && var2.getDirectEntity() == TrainingSession.this.owner) {
            TrainingSession.this.tactics.swordDuel.incomingMelee(TrainingSession.this.roundTick);
         }
         if (var2.getDirectEntity() instanceof TrainingCrystal) {
            TrainingSession.this.lastBotDamage = TrainingSession.this.lastBotDamage + this.damageThisHit;
         }

         if (TrainingSession.this.attempt != null && var2.is(DamageTypes.BAD_RESPAWN_POINT)) {
            TrainingSession.this.attempt.anchorHit(this.damageThisHit);
         }
      }

      public float applyItemBlocking(ServerLevel var1, DamageSource var2, float var3) {
         InteractionHand var4 = this.getUsedItemHand();
         ItemStack var5 = this.getItemBlockingWith();
         BlocksAttacks var6 = var5 == null ? null : (BlocksAttacks)var5.get(DataComponents.BLOCKS_ATTACKS);
         float var7 = super.applyItemBlocking(var1, var2, var3);
         if (var6 != null && !(var7 <= 0.0F) && TrainingSession.this.attempt != null && TrainingSession.this.equipment != null) {
            int var8 = var5.getDamageValue();
            var5.hurtAndBreak(var6.itemDamage().apply(var7), this, var4);
            boolean var9 = var5.isEmpty();
            if (var2.getEntity() == TrainingSession.this.owner) {
               TrainingSession.this.attempt.shieldBlocked(var7);
               if (TrainingSession.this.options.drill() == Drill.SHIELD) {
                  TrainingSession.this.recordRepetition();
               }

               if (var9) {
                  TrainingSession.this.attempt.shieldBreak();
                  if (TrainingSession.this.options.drill() == Drill.STUN_WEB && this.stunImpulseAt != TrainingSession.this.roundTick)
                     TrainingSession.this.attempt.finish(false, "Schild ohne gültigen Stun verbraucht");
               } else if (var5.getDamageValue() > var8) {
                  TrainingSession.this.attempt.event("Schild âˆ’" + (var5.getDamageValue() - var8) + " Haltbarkeit");
               }
            }

            if (var9) {
               this.stopUsingItem();
               TrainingSession.this.equipment.broken(this);
               int pause = TrainingSession.this.options.shieldBreakTicks(TrainingSession.this.settings.mode());
               BlocksAttacks replacement = this.getItemInHand(TrainingSession.this.equipment.shieldHand).get(DataComponents.BLOCKS_ATTACKS);
               if (pause >= 0 && replacement != null) this.pauseShield(pause, replacement, true);
            }

            return var7;
         } else {
            return var7;
         }
      }

      protected void blockUsingItem(ServerLevel var1, LivingEntity var2, DamageSource var3, float var4) {
         // The callback precedes the recoil of a successfully blocked impact. An attack from behind
         // skips it even while the shield is raised. The stun drill applies its own validated lift below.
         if (var3.getDirectEntity() == var2 && var2 == TrainingSession.this.owner && var3.is(DamageTypes.PLAYER_ATTACK)) {
            this.blockedMelee = var3;
            this.blockedMeleeTick = TrainingSession.this.roundTick;
         }
         super.blockUsingItem(var1, var2, var3, var4);
         ItemStack var5 = this.getItemBlockingWith();
         BlocksAttacks var6 = var5 == null ? null : (BlocksAttacks)var5.get(DataComponents.BLOCKS_ATTACKS);
         if (var6 != null && TrainingSession.this.attempt != null) {
            float var7 = var2.getSecondsToDisableBlocking();
            if (TrainingSession.this.options.drill() == Drill.STUN_WEB) {
               if (var2 != TrainingSession.this.owner || var3.getDirectEntity() != var2 || !var3.is(DamageTypes.PLAYER_ATTACK)
                     || !(var2.getMainHandItem().is(ItemTags.AXES) || var2.getMainHandItem().is(ItemTags.SWORDS))) {
                  return;
               }
               float validStun = TrainingSession.this.attempt.doubleClickDisable(TrainingSession.this.webClickOnBot,
                  var2.getMainHandItem().is(ItemTags.AXES) ? var7 : 0);
               if (var7 > 0 && validStun == 0) {
                  var6.disable(var1, this, var7, var5);
                  this.pauseShield(Math.round(20 * var7 * var6.disableCooldownScale()), var6, false);
                  this.pendingWebClick = TrainingSession.this.webClickOnBot;
                  this.pendingWebTick = TrainingSession.this.roundTick;
                  this.pendingWebDuration = Math.round(20 * var7 * var6.disableCooldownScale());
                  return;
               }
               var7 = validStun;
            }
            int var8 = Math.round(20.0F * var7 * var6.disableCooldownScale());
            if (var8 > 0) {
               var6.disable(var1, this, var7, var5);
               DrillOptions options = TrainingSession.this.options;
               int configured = options.shieldBreakTicks(TrainingSession.this.settings.mode());
               int pause = configured >= 0 ? configured : (options.instantReblock() ? 0 : var8);
               this.pauseShield(pause, var6, configured >= 0 || options.instantReblock() && options.holdShield());

               if (var2 == TrainingSession.this.owner) {
                  int raiseTicks = options.drill() == Drill.STUN_SLAM && configured < 0 && !options.instantReblock()
                     ? var6.blockDelayTicks() : 0;
                  TrainingSession.this.attempt.shieldStun(pause + raiseTicks);
                  if (options.drill() == Drill.WIND_STUN_SLAM && !scoredWindFlight && windTargetAirborne() && pause > 0
                        && var2.getMainHandItem().is(ItemTags.AXES) && var3.getDirectEntity() == owner) airborneAxeStun = true;
                  if (options.drill() != Drill.STUN_SLAM && options.drill() != Drill.STUN_WEB && options.drill() != Drill.WIND_STUN_SLAM
                        && var2.getMainHandItem().is(ItemTags.AXES) && var3.getDirectEntity() == owner && pause > 0) {
                     armSecondarySlam(pause);
                  }
                  if (TrainingSession.this.options.drill() == Drill.STUN_WEB && this.onGround()) {
                     this.stunImpulseAt = TrainingSession.this.roundTick;
                     super.knockback(0.4, var2.getX() - this.getX(), var2.getZ() - this.getZ(), var3, var4, false);
                  }
               }
            }
         }
      }

      public void hurtArmor(DamageSource var1, float var2) {
         this.doHurtEquipment(var1, var2, new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET});
      }

      public boolean shouldBeSaved() {
         return false;
      }

      public boolean saveAsPassenger(ValueOutput var1) {
         return false;
      }

      public boolean canUsePortal(boolean var1) {
         return false;
      }

      protected void dropAllDeathLoot(ServerLevel var1, DamageSource var2) {
      }
   }
}
