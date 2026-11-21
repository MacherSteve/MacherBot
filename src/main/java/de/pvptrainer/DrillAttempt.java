package de.pvptrainer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class DrillAttempt {
   private static final int NEVER = -100000;
   private final DrillOptions options;
   private final Drill drill;
   private final Deque<String> events = new ArrayDeque<>();
   private final double control;
   private final int variant;
   private int tick;
   private int started = -100000;
   private int swordAt = -100000;
   private int lastCrystal = -100000;
   private int stunUntil = -100000;
   private int lastOwnerHit = -100000;
   private int anchorAt = -100000;
   private int launched = -100000;
   private int caught = -100000;
   private int jumpAt = -100000;
   private int firstSmash = -100000;
   private int flightEnded = -100000;
   private int lavaOut = -100000;
   private int rescueTick = -100000;
   private int popAt = -100000;
   private int swordHits;
   private int crystalHits;
   private int explosions;
   private int axeStunAt = -100000;
   private boolean airborneTarget;
   private boolean burstLifted;
   private boolean swappedArmor;
   private Vec3 startOwner;
   private int lavaPlacedAt = -100000;
   private int webAt = -100000;
   private int lavaInAt = -100000;
   private BlockPos lastLavaSource;
   private final java.util.Set<BlockPos> trapSites = new java.util.HashSet<>();
   private int trapProgress;
   private BlockPos lastWebSite;
   private int startingFood = -1;
   private float damageOnOwner;
   private boolean validAnchorOpening;
   private boolean wasGliding;
   private boolean hadAir;
   private boolean slamWasAirborne;
   private int supportedTicks;
   private int wallImpactAt = NEVER;
   private double previousSlamY = Double.NaN;
   private boolean inLava;
   private boolean covered;
   private boolean protectionSeen;
   private boolean offhandReady;
   private int playerPops;
   private int refillTotal;
   private boolean unscored;
   private boolean done;
   private boolean won;
   private boolean touched;
   private double anchorSelfDamage;
   private double anchorDamage;
   private double anchorOwnerHealth;
   private double measuredControl = -1.0;
   private Vec3 firstCrystalSide;
   private Vec3 ownerKnockback;
   private Vec3 botHitOrigin;
   private int displacementAt = -100000;
   private BlockPos rescueSource;
   private String message;
   private String lastTimingFault;
   private int airSwordAt = -100000;
   private boolean airSwordStartedWindow;
   private boolean openingLanded;
   private int firstGuardClick = -100000;
   private long firstGuardSequence = -2;
   private boolean stunTargetLifted;


   float doubleClickDisable(long sequence, float axeSeconds) {
      if (this.drill != Drill.STUN_WEB || this.done || sequence <= 0) return 0;
      if (sequence == firstGuardSequence) return 0;
      if (sequence == firstGuardSequence + 1 && tick - firstGuardClick <= 4 && axeSeconds > 0) {
         firstGuardSequence = -2;
         return axeSeconds;
      }
      firstGuardClick = tick;
      firstGuardSequence = sequence;
      return 0;
   }

   void timingFault(String var1) {
      if (!this.done && !var1.equals(this.lastTimingFault)) {
         this.lastTimingFault = var1;
         this.step(var1);
      }
   }

   String timingHint() {
      return this.lastTimingFault;
   }

   void clearTimingFault() {
      this.lastTimingFault = null;
   }

   DrillAttempt(DrillOptions var1, double var2, int var4) {
      this.options = var1;
      this.drill = var1.drill();
      this.control = var2;
      this.variant = var4;
      this.message = var1.hints() ? this.drill.hint() : this.drill.label();
   }

   boolean done() {
      return this.done;
   }

   boolean touched() {
      return this.touched;
   }

   boolean won() {
      return this.won;
   }

   boolean unscored() {
      return this.unscored;
   }

   int duration() {
      return this.tick;
   }

   double control() {
      return this.measuredControl;
   }

   String progress() {
      return this.message;
   }

   List<String> timeline() {
      return List.copyOf(this.events);
   }

   static String decimal(double var0) {
      return String.format(Locale.GERMANY, "%.1f", var0);
   }

   void event(String var1) {
      if (!this.done) {
         this.touched = true;
         if (this.started == -100000) {
            this.started = this.tick;
         }

         if (this.events.size() == 28) {
            this.events.removeFirst();
         }

         this.events.addLast("T+" + this.tick + "  " + var1);
      }
   }

   private void step(String var1) {
      if (!this.done) {
         this.event(var1);
         this.message = var1;
      }
   }

   void finish(boolean var1, String var2) {
      if (!this.done) {
         if (!var1 && this.lastTimingFault == null && this.drill == Drill.STUN_SLAM && !var2.contains("Mace") && !var2.contains("Axt")) {
            var2 = var2 + " · " + this.slamFailure();
         }

         if (!var1 && this.lastTimingFault != null && !var2.contains(this.lastTimingFault)) {
            var2 = this.lastTimingFault + " · " + var2;
         }

         this.event(var2);
         this.message = var2;
         this.done = true;
         this.won = var1;
      }
   }

   private boolean recent(int var1) {
      return this.tick - var1 <= this.options.windowTicks();
   }

   void tick(int var1, ServerPlayer var2, LivingEntity var3) {
      this.tick = var1;
      if (!this.done) {
         if (this.startOwner == null) {
            this.startOwner = var2.position();
         }

         if (this.startingFood < 0 && this.drill == Drill.HEAL) {
            this.startingFood = healingItems(var2);
         }

         if (this.swordAt != -100000 && this.isSwordCrystalSequence()) {
            if (var3.onGround() && (this.drill.requiresPopChain() && this.airborneTarget || !(var3.getDeltaMovement().y > 0.05))) {
               if (this.airborneTarget && !this.openingLanded) {
                  this.openingLanded = true;
                  if (this.drill.requiresPopChain()) {
                     this.finish(false, "Der Bot ist vor dem letzten Pop gelandet.");
                     return;
                  }
                  if (this.crystalHits == 0) {
                     this.timingFault("Kristall 1 zu spät: Ziel nach " + (this.tick - this.swordAt) + " Ticks gelandet");
                  } else if (this.needsAirSword()) {
                     this.timingFault("Nahkampftreffer 2 zu spät: Ziel " + (this.tick - this.lastCrystal) + " Ticks nach Kristall 1 gelandet");
                  } else if (this.options.airborneFollowups() && (this.drill != Drill.TAP_ANCHOR || this.crystalHits < 2)) {
                     this.timingFault("Kristall " + (this.crystalHits + 1) + " zu spät: Ziel bereits gelandet");
                  }
               }
            } else {
               this.airborneTarget = true;
            }

            int var4 = this.tick - (this.crystalHits == 0 ? this.swordAt : this.lastCrystal);
            int var5 = this.crystalHits == 0 ? (this.drill == Drill.HIT_CRYSTAL ? 10 : 9) : this.options.windowTicks();
            if (var4 > var5 && !this.done) {
               String var6 = this.needsAirSword()
                  ? "Nahkampftreffer 2"
                  : (this.drill == Drill.TAP_ANCHOR && this.crystalHits == 2 ? "Anker" : "Kristall " + (this.crystalHits + 1));
               this.finish(false, this.lastTimingFault != null ? this.lastTimingFault : var6 + " zu spät (" + var4 + " Ticks, Fenster bis " + var5 + ")");
            }
         }

         if (this.firstSmash != -100000 && var2.getDeltaMovement().y > 0.1) {
            this.burstLifted = true;
         }

         if (this.wasGliding && !var2.isFallFlying()) {
            this.flightEnded = this.tick;
            this.event("Gleitflug beendet");
            this.swappedArmor = !var2.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
            if (this.swappedArmor) {
               this.event("Elytra abgelegt");
            }
         }

         if (this.flightEnded != -100000 && !this.swappedArmor && !var2.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            this.swappedArmor = true;
            this.event("Elytra abgelegt");
         }

         this.wasGliding = var2.isFallFlying();
         if (!var2.onGround()) {
            this.hadAir = true;
         }

         if (var2.onGround() && this.hadAir) {
            if (this.firstSmash != -100000 || this.caught != -100000 || this.flightEnded != -100000 || this.launched != -100000) {
               this.event("Bodenkontakt");
            }


            this.hadAir = false;
            this.firstSmash = this.caught = this.flightEnded = this.launched = -100000;
         }

         if (this.displacementAt != -100000 && this.tick - this.displacementAt >= 4) {
            Vec3 var8 = var3.position().subtract(this.botHitOrigin);
            this.event("Bot-Versatz " + decimal(var8.horizontalDistance()) + " Bl. horizontal, " + decimal(var8.y) + " Bl. vertikal");
            this.displacementAt = -100000;
         }

         this.settleAnchor(var2, this.tick);
         if (this.drill == Drill.JUMP_RESET && this.ownerKnockback != null && this.tick - this.lastOwnerHit >= 6) {
            double var9 = var2.position().subtract(this.ownerKnockback).horizontalDistance();
            boolean var14 = Math.abs(this.jumpAt - this.lastOwnerHit) <= 2;
            if (!var14) {
               this.measuredControl = var9;
               this.finish(false, "Kontrollversuch ohne passenden Sprung: " + decimal(var9) + " Bl.");
            } else {
               String var7 = this.control >= 0.0 ? " · Kontrolle " + decimal(this.control) + " Bl." : " · Kontrolle ohne Sprung noch offen";
               this.finish(true, "Sprung " + (this.jumpAt - this.lastOwnerHit) + " Ticks · Versatz " + decimal(var9) + " Bl." + var7);
            }
         }

         if (this.drill == Drill.HEAD_WEB) {
            var head = BlockPos.containing(var3.getEyePosition());
            this.checkHeadTrap(head, var3.level().getBlockState(head).getBlock());
         } else if (this.drill == Drill.OBI_TRAP) {
            this.checkObsidianTrap(var3.level(), var3.blockPosition());
         }
         if (this.drill == Drill.STUN_WEB && this.axeStunAt != NEVER) {
            if (!var3.onGround()) this.stunTargetLifted = true;
            if (var3.onGround() && (this.stunTargetLifted || this.tick - this.axeStunAt > 1)) {
               this.finish(false, "Der Bot hat den Boden vor dem Netz berührt.");
               return;
            }
            if (this.tick >= this.stunUntil) {
               this.finish(false, "Das Schild ist wieder aktiv.");
               return;
            }
         }
         if (this.drill == Drill.STUN_WEB
            && !var3.onGround()
            && this.tick < this.stunUntil
            && this.webAt != -100000
            && this.lastWebSite != null
            && var3.level().getBlockState(this.lastWebSite).is(Blocks.COBWEB)
            && var3.getBoundingBox().intersects(new AABB(this.lastWebSite))) {
            this.finish(true, "Bot im neu platzierten Netz, Schild noch deaktiviert");
         }

         if (this.drill == Drill.WEB_DRAIN && this.rescueSource != null && !inWeb(var3)) {
            this.finish(false, "Bot aus dem Netz entkommen");
         }

         if (this.drill == Drill.RE_LAVA) {
            boolean var10 = touchesLava(var3);
            if (this.inLava && !var10 && this.rescueSource != null) {
               this.lavaOut = this.tick;
               this.step("Ersten Lavakontakt gelöscht");
            }

            if (!this.inLava && var10) {
               boolean var12 = this.lastLavaSource != null && var3.blockPosition().distSqr(this.lastLavaSource) <= 20.0;
               if (var12 && this.lavaOut != -100000 && this.lavaPlacedAt >= this.lavaOut) {
                  this.finish(true, "Zweite Lava auf das gelöschte Ziel gesetzt");
               } else if (var12 && this.lavaInAt == -100000) {
                  this.lavaInAt = this.tick;
                  this.step("Erster Lavakontakt");
               }
            }

            this.inLava = var10;
         }

         if (this.drill == Drill.DOUBLE_HAND && this.popAt != -100000) {
            boolean var11 = var2.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
            boolean var13 = var2.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
            if (!this.protectionSeen && (var11 || var13)) {
               this.protectionSeen = true;
               this.event("Erneut geschützt nach " + (this.tick - this.popAt) + " Ticks");
            }

            if (!this.offhandReady && var13) {
               this.offhandReady = true;
               this.step("Nebenhand bereit nach " + (this.tick - this.popAt) + " Ticks");
            }
         }

         if (this.drill.series() && !this.drill.centeredMaceDrill() && this.drill != Drill.PERFECT_WIND_CHARGE && this.drill != Drill.RETOTEM && this.drill != Drill.STUN_SLAM && this.tick >= 1200) {
            this.finish(false, "Zeit abgelaufen");
         }
      }
   }

   void settleAnchor(ServerPlayer var1, int var2) {
      if (!this.done && this.anchorAt != -100000 && var2 > this.anchorAt) {
         this.tick = var2;
         double var3 = Math.max(this.anchorSelfDamage, this.anchorOwnerHealth - health(var1));
         this.event("Anker: Bot " + decimal(this.anchorDamage) + " LP · selbst " + decimal(var3) + " LP");
         if (!(this.anchorDamage > 0.0)
            || (this.drill != Drill.TAP_ANCHOR || !this.validAnchorOpening)
               && (this.drill != Drill.SAFE_ANCHOR || !this.covered || !(this.anchorDamage > var3))) {
            if (this.drill == Drill.SAFE_ANCHOR || this.drill == Drill.TAP_ANCHOR) {
               this.finish(false, "Anker: Deckung, Eröffnung oder Schadensvorteil fehlte");
            }
         } else {
            this.finish(true, "Ankerfolge gelungen");
         }

         this.anchorAt = -100000;
      }
   }

   private static boolean touchesLava(LivingEntity var0) {
      AABB var1 = var0.getBoundingBox().deflate(0.001);
      Level var2 = var0.level();

      for (BlockPos var3 : BlockPos.betweenClosed(BlockPos.containing(var1.minX, var1.minY, var1.minZ), BlockPos.containing(var1.maxX, var1.maxY, var1.maxZ))) {
         FluidState var5 = var2.getFluidState(var3);
         if (var5.is(FluidTags.LAVA) && var3.getY() + var5.getHeight(var2, var3) > var1.minY) {
            return true;
         }
      }

      return false;
   }

   static boolean inWeb(LivingEntity var0) {
      AABB var1 = var0.getBoundingBox().deflate(0.001);

      for (BlockPos var2 : BlockPos.betweenClosed(BlockPos.containing(var1.minX, var1.minY, var1.minZ), BlockPos.containing(var1.maxX, var1.maxY, var1.maxZ))) {
         if (var0.level().getBlockState(var2).is(Blocks.COBWEB)) {
            return true;
         }
      }

      return false;
   }

   static float health(LivingEntity var0) {
      return var0.getHealth() + var0.getAbsorptionAmount();
   }

   int reaction() {
      return this.options.reactionTicks() + (this.options.variableTiming() ? this.variant % 5 * 2 : 0);
   }

   int time() {
      return this.tick;
   }

   boolean allowRescue() {
      return this.rescueSource == null
         && !this.done
         && (
            this.drill == Drill.WEB_DRAIN && this.tick >= this.reaction()
               || this.drill == Drill.RE_LAVA && this.inLava && this.lavaInAt != -100000 && this.tick - this.lavaInAt >= this.reaction()
         );
   }

   void checkHeadTrap(BlockPos head, net.minecraft.world.level.block.Block block) {
      if (this.drill == Drill.HEAD_WEB && head.equals(this.lastWebSite) && block == Blocks.COBWEB) {
         this.finish(true, "Der Kopf des Bots steckt im selbst platzierten Netz.");
      }
   }

   void checkObsidianTrap(Level level, BlockPos feet) {
      trapSites.removeIf(pos -> {
         var state = level.getBlockState(pos);
         return !state.is(Blocks.OBSIDIAN) && !(state.is(Blocks.LAVA) && state.getFluidState().isSource());
      });
      BlockPos head = feet.above();
      boolean bottom = trapSites.contains(feet) && level.getBlockState(feet).is(Blocks.OBSIDIAN);
      boolean top = trapSites.contains(head) && level.getBlockState(head).is(Blocks.OBSIDIAN);
      if (bottom && top && ObsidianEnclosure.closed(level, feet)) {
         finish(true, "Kopf und Füße sind in deinem Obsidian. Alle Fluchtwege sind zu.");
         return;
      }
      int progress = bottom && top ? 3 : bottom ? 1 : top ? 2 : 0;
      if (progress != trapProgress) {
         trapProgress = progress;
         step(progress == 3 ? "Obsidian sitzt · Fluchtweg mit Blöcken schließen"
            : bottom ? "Füße zu · Obsidian am Kopf fehlt" : top ? "Kopf zu · Obsidian an den Füßen fehlt" : "Kopf und Füße offen");
      }
   }

   void trapBlockPlaced(BlockPos pos) {
      if (drill == Drill.OBI_TRAP && !done) trapSites.add(pos.immutable());
   }

   void lavaPlaced(BlockPos var1) {
      this.lavaPlacedAt = this.tick;
      this.lastLavaSource = var1.immutable();
      trapBlockPlaced(var1);
      this.event("Lava gesetzt");
   }

   void webPlaced(BlockPos var1) {
      if (this.drill == Drill.HEAD_WEB || this.tick < this.stunUntil) {
         this.webAt = this.tick;
         this.lastWebSite = var1.immutable();
         this.event(this.drill == Drill.HEAD_WEB ? "Netz platziert" : "Netz nach Stun gesetzt");
      }
   }

   void waterPlaced(BlockPos var1) {
      this.rescueSource = var1.immutable();
      this.rescueTick = this.tick;
      this.step("Bot setzt Rettungswasser");
   }

   BlockPos rescueSource() {
      return this.rescueSource;
   }

   int rescueTick() {
      return this.rescueTick;
   }

   void waterDrawn(BlockPos var1, LivingEntity var2) {
      if (this.drill == Drill.WEB_DRAIN && var1.equals(this.rescueSource) && inWeb(var2)) {
         this.finish(true, "Wasserquelle nach " + (this.tick - this.rescueTick) + " Ticks aufgenommen");
      }
   }

   boolean readyForDefense(ServerPlayer var1) {
      if (this.drill != Drill.RETOTEM && this.popAt == -100000) {
         boolean var2 = var1.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
         boolean var3 = var1.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
         return var3 && (this.drill != Drill.DOUBLE_HAND || var2);
      } else {
         return true;
      }
   }

   int playerPops() {
      return this.playerPops;
   }

   void startRefill(int var1) {
      this.refillTotal = var1;
      this.message = this.refillProgress();
   }

   String refillProgress() {
      return this.playerPops + "/" + this.refillTotal + " Totems verbraucht";
   }

   void playerPop(int var1) {
      if (!this.done) {
         this.playerPops++;
         this.refillTotal = Math.max(this.refillTotal, this.playerPops + var1);
         if (this.playerPops == this.refillTotal && var1 == 0) {
            this.finish(true, this.refillProgress());
         } else {
            this.step(this.refillProgress());
         }
      }
   }

   void playerPop() {
      if (!this.done) {
         this.playerPops++;
         this.event("Eigenes Totem ausgelöst");
      }
   }

   void defenseUnavailable(String var1) {
      if (!this.done) {
         this.unscored = this.drill == Drill.RETOTEM || this.drill == Drill.WIND_STUN_SLAM;
         this.finish(false, var1);
      }
   }

   void defenseBlast(boolean var1, boolean var2, boolean var3, float var4, boolean var5) {
      this.defenseBlast(var1, var2, var3, var4, var5, true);
   }

   void defenseBlast(boolean var1, boolean var2, boolean var3, float var4, boolean var5, boolean var6) {
      if (this.drill == Drill.DOUBLE_HAND && !this.done && var5 && (var3 || !(var4 <= 0.0F))) {
         if (this.popAt != -100000) {
            boolean var7 = var1 || var2;
            if (!var6) {
               this.step(
                  var7 ? "Folgetreffer abgefangen · letzter Kristall offen" : "Zwischentreffer überlebt · vor dem letzten Kristall ein Totem bereithalten"
               );
            } else {
               if (var7) {
                  this.finish(true, "Folgekristall mit Totemschutz überlebt");
               } else {
                  this.finish(false, "Totem beim letzten Folgetreffer fehlte");
               }
            }
         } else if (var3) {
            if (var1 && var2) {
               this.popAt = this.tick;
               this.step("Erster Pop mit beiden Händen · Folgekristall offen");
            } else {
               this.finish(false, "Beim ersten Pop fehlte ein Totem in einer Hand");
            }
         }
      }
   }

   void botPop() {
      this.step("Bot-Totem ausgelöst");
   }

   void announceSwing() {
      this.message = "Schlag in 3 Ticks";
   }

   void jump() {
      this.jumpAt = this.tick;
      if (this.drill == Drill.JUMP_RESET || this.drill == Drill.PUNISH_CRIT) {
         this.event("Eigener Sprung");
      }
   }

   void glideStarted() {
      if (this.drill == Drill.ELYTRA_SMASH && !this.done) {
         this.wasGliding = true;
         this.flightEnded = -100000;
         this.swappedArmor = false;
         this.hadAir = true;
         this.event("Gleitflug begonnen");
      }
   }

   void startWebDrop() {

      hadAir = true;
      step("Luftstart über dem Kopfnetz");
   }

   void launch() {
      this.launched = this.tick;
      this.event("Windladung hebt Spieler an");
   }

   void catchPearl() {
      this.caught = this.tick;
      this.step("Pearl Catch · Luft-Teleport");
   }

   private static boolean heals(ItemStack var0) {
      return var0.is(Items.GOLDEN_APPLE) || var0.is(Items.ENCHANTED_GOLDEN_APPLE) || MacherItems.isHealingHead(var0);
   }

   private static int healingItems(ServerPlayer var0) {
      int var1 = 0;

      for (ItemStack var2 : var0.getInventory().getNonEquipmentItems()) {
         if (heals(var2)) {
            var1 += var2.getCount();
         }
      }

      if (heals(var0.getOffhandItem())) {
         var1 += var0.getOffhandItem().getCount();
      }

      return var1;
   }

   void ate(ServerPlayer var1, ItemStack var2) {
      if (this.drill == Drill.HEAL && heals(var2)) {
         this.event(var2.getHoverName().getString() + " verzehrt · " + decimal(this.damageOnOwner) + " LP Gegenschaden");
         int var3 = Math.max(1, this.startingFood - healingItems(var1));
         this.finish(true, "Heilung abgeschlossen · " + var3 + " Heilitem verbraucht");
      }
   }

   void playerHit(ServerPlayer var1, float var2) {
      this.damageOnOwner += var2;
      this.event("Gegentreffer " + decimal(var2) + " LP");
      if (this.drill == Drill.SPACING) {
         this.finish(false, "Bot landete den Gegentreffer");
      }

      if (this.drill == Drill.HIT_SELECT && this.swordHits > 0) {
         this.finish(false, "Gegentreffer vor dem Anschluss");
      }

      if (this.drill != Drill.JUMP_RESET || this.ownerKnockback == null) {
         this.lastOwnerHit = this.tick;
         this.ownerKnockback = var1.position();
      }
   }

   boolean needsAirSword() {
      return this.drill == Drill.TRIPLE_TAP && this.crystalHits == 1 && this.airSwordAt == -100000;
   }

   void swordHit(ServerPlayer var1, LivingEntity var2, float var3, float var4) {
      this.swordContact(var1, var2, var3, var4, !var2.onGround() || var2.getDeltaMovement().y > 0.02, true, true);
   }

   void swordContact(ServerPlayer var1, LivingEntity var2, float var3, float var4, boolean var5, boolean var6, boolean var7) {
      if (!this.done) {
         if (this.drill.requiresPopChain() && this.swordAt != NEVER && this.airborneTarget && var2.onGround()) {
            this.finish(false, "Der Bot ist vor dem letzten Pop gelandet.");
            return;
         }
         if (this.needsAirSword()) {
            if (var5 && this.recent(this.lastCrystal) && !this.openingLanded) {
               this.airSwordAt = this.tick;
               this.airSwordStartedWindow = var6 && var7;
               this.clearTimingFault();
               int var11 = this.tick - this.lastCrystal;
               this.step(
                  "Nahkampftreffer 2 in der Luft · "
                     + (var1.getMainHandItem().is(Items.TOTEM_OF_UNDYING) ? "Totem · " : "Schwert · ")
                     + var11
                     + (var11 == 1 ? " Tick" : " Ticks")
                     + " nach Kristall 1 · "
                     + (var6 ? decimal(var3) + " LP" : "Kontakt im Trefferschutz")
               );
            } else {
               this.timingFault("Nahkampftreffer 2 zu spät: Ziel bereits gelandet oder Kombofenster vorbei");
            }
         } else if (!var6) {
            if (this.drill == Drill.TRIPLE_TAP && this.swordAt != -100000 && this.crystalHits == 0) {
               this.timingFault("Nahkampftreffer 2 zu früh: Zuerst Kristall 1 zünden");
            }
         } else {
            boolean var8 = var1.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
            if (!this.isSwordCrystalSequence() || !var8 || var1.isSprinting() && !(var4 <= 0.9F)) {
               this.event((var8 ? "Totem-Sprinttreffer " : "Schwertkontakt ") + Math.round(var4 * 100.0F) + " % · " + decimal(var3) + " LP");
               if (this.isSwordCrystalSequence()) {
                  this.clearTimingFault();
               }

               this.swordAt = this.tick;
               this.airborneTarget = var5;
               this.openingLanded = false;
               this.crystalHits = 0;
               this.airSwordAt = this.lastCrystal = -100000;
               this.airSwordStartedWindow = false;
               this.firstCrystalSide = null;
               this.swordHits++;
               if (this.drill == Drill.SPACING) {
                  int var9 = this.options.comboLength();
                  String var10 = this.swordHits + "/" + var9 + " Treffer vor dem ersten Gegentreffer";
                  if (this.swordHits >= var9) {
                     this.finish(true, var10);
                  } else {
                     this.step(var10);
                  }
               }

               if (this.drill == Drill.HIT_SELECT) {
                  if (this.lastOwnerHit == -100000) {
                     this.finish(false, "Erst den Bot treffen lassen");
                  } else if (this.swordHits != 1 || this.tick - this.lastOwnerHit <= 8 && !var1.onGround()) {
                     if (this.swordHits >= 2 && var1.onGround() && this.recent(this.lastOwnerHit)) {
                        this.finish(true, "Konter im Rückstoß und Anschluss nach Landung");
                     }
                  } else {
                     this.finish(false, "Konter außerhalb des Rückstoßes");
                  }
               }
            } else {
               this.timingFault("Eröffnung mit Totem: ansprinten und über 90 % aufladen oder das Schwert nehmen");
            }
         }
      }
   }

   void critical() {
      this.event("Kritischer Treffer");
      if (this.drill == Drill.PUNISH_CRIT && this.recent(this.lastOwnerHit) && this.jumpAt < this.lastOwnerHit && this.swordAt == this.tick) {
         this.finish(true, "Kritischer Konter aus gegnerischem Rückstoß");
      }
   }

   void shieldBlocked(float var1) {
      this.event("Schild blockt " + decimal(var1));
   }

   void shieldBreak() {
      this.step("Schild durch Verschleiß gebrochen");
      if (this.drill == Drill.SHIELD_DRAIN) {
         this.finish(true, "Schild vollständig aufgebraucht");
      }
   }

   boolean shieldWindowOpen() {
      return tick < stunUntil;
   }

   void shieldStun(int var1) {
      this.stunUntil = this.tick + var1;
      this.axeStunAt = this.tick;
      this.stunTargetLifted = false;
      if (this.drill == Drill.STUN_SLAM) {
         this.clearTimingFault();
      }

      this.webAt = -100000;
      this.lastWebSite = null;
      this.step("Schild deaktiviert für " + var1 + " Ticks");
   }

   void axeContact(boolean var1, int var2) {
      if (!this.done && this.axeStunAt != this.tick && this.tick >= this.stunUntil) {
         if (var1) {
            this.timingFault("Axt traf die ungedeckte Seite · kein Schild-Stun");
         } else if (var2 > 0) {
            this.timingFault("Axt " + ticksText(var2) + " zu früh · Schild noch im Hochheben");
         } else {
            this.timingFault("Axt traf bei gesenktem Schild · kein Stun");
         }
      }
   }

   void maceContact(double var1, boolean var3, boolean var4) {
      if (!this.done) {
         if (this.axeStunAt == -100000) {
            this.timingFault("Mace zu früh · vor dem Axt-Stun");
         } else if (this.tick >= this.stunUntil) {
            this.timingFault("Mace zu spät · Schildfenster seit " + ticksText(this.tick - this.stunUntil) + " geschlossen");
         } else if (var3) {
            this.timingFault("Mace zu spät · Bodenkontakt " + ticksText(this.tick - this.axeStunAt) + " nach der Axt");
         } else if (!var4 && var1 <= 1.5) {
            this.timingFault("Mace zu früh · erst " + decimal(var1) + " Bl. Fall, benötigt sind mehr als 1,5");
         } else {
            this.timingFault("Mace " + ticksText(this.tick - this.axeStunAt) + " nach der Axt · kein wirksamer Smash");
         }
      }
   }

   String slamFailure() {
      return this.axeStunAt == -100000
         ? "Axt-Stun fehlt · frontal auf das erhobene Schild schlagen"
         : "Nach dem Axt-Stun bei T+" + this.axeStunAt + " fehlt ein wirksamer Mace-Smash";
   }

   private static String ticksText(int var0) {
      return var0 == 1 ? "1 Tick" : var0 + " Ticks";
   }

   void slamWallImpact() {
      if (!done && wallImpactAt == NEVER) wallImpactAt = tick;
   }

   void observeSlamLanding(ServerPlayer player) {
      if (done) return;
      AABB body = player.getBoundingBox();
      AABB feet = new AABB(body.minX, body.minY - 0.002, body.minZ, body.maxX, body.minY, body.maxZ);
      boolean supported = player.level().getBlockCollisions(player, feet).iterator().hasNext();
      double y = player.getY();
      boolean rising = y > previousSlamY + 0.001 || player.getDeltaMovement().y > 0.01;
      previousSlamY = y;
      if (!supported) slamWasAirborne = true;
      if (rising) wallImpactAt = NEVER;
      boolean settled = supported && (player.onGround() || player.verticalCollisionBelow) && !rising;
      supportedTicks = settled && slamWasAirborne ? supportedTicks + 1 : 0;
      // Movement, the attack and the Wind Burst impulse can arrive on adjacent ticks.
      if (supportedTicks >= 3) slamLanded();
      else if (wallImpactAt != NEVER && tick - wallImpactAt >= 3) finish(false, "Stun Slam: Aufprall ohne Auftrieb");
   }

   void slamLanded() {
      if (this.axeStunAt != -100000 && this.lastTimingFault == null) {
         this.timingFault("Mace-Smash zu spät · Landung " + ticksText(this.tick - this.axeStunAt) + " nach der Axt");
      }

      this.finish(false, "Stun Slam: Landung");
   }

   void smash(ServerPlayer var1, boolean var2) {
      this.event("Smash · Fallstrecke " + decimal(var1.fallDistance) + " Bl.");
      switch (this.drill) {
         case SMASH:
            if (this.recent(this.launched)) {
               this.finish(true, "Smash nach eigener Windladung");
            }
            break;
         case WIND_CHAIN:
            if (this.recent(this.firstSmash) && this.burstLifted) {
               this.finish(true, "Zwei Smashes nach Windstoß ohne Bodenkontakt");
            } else if (var1.getMainHandItem().getEnchantments().keySet().stream().anyMatch(var0 -> var0.is(Enchantments.WIND_BURST))) {
               this.firstSmash = this.tick;
               this.burstLifted = false;
               this.step("Wind-Burst-Smash · Folgetreffer offen");
            } else {
               this.step("Smash mit einer Mace ohne Wind Burst");
            }
            break;
         case STUN_SLAM:
            if (!var2 && this.tick < this.stunUntil) {
               this.finish(true, "Smash im offenen Schildfenster · " + ticksText(this.tick - this.axeStunAt) + " nach der Axt");
            } else {
               this.maceContact(var1.fallDistance, var1.onGround(), true);
            }
            break;
         case BACKSTAB:
            if (var2) {
               this.finish(true, "Smash am aktiven Schild vorbei");
            }
         case SHIELD_DRAIN:
         default:
            break;
         case PEARL_CATCH:
            if (this.recent(this.caught)) {
               this.finish(true, "Smash nach Luft-Teleport");
            }
            break;
         case ELYTRA_SMASH:
            if (this.recent(this.flightEnded) || this.wasGliding && !var1.isFallFlying()) {
               this.finish(true, "Smash nach Gleitflug");
            }
      }
   }

   void observeSecondarySlam(int roundTick, ServerPlayer player) {
      tick = roundTick;
      observeSlamLanding(player);
   }

   boolean unusedSlamWindowExpired() {
      return tick >= stunUntil && !slamWasAirborne && wallImpactAt == NEVER;
   }

   void anchor(ServerPlayer var1, BlockPos var2, boolean var3) {
      this.validAnchorOpening = this.crystalHits >= 2 && this.recent(this.lastCrystal);
      this.anchorAt = this.tick;
      this.anchorOwnerHealth = health(var1);
      this.anchorDamage = this.anchorSelfDamage = 0.0;
      this.covered = var3;
      this.event("Geladener Anker bei " + var2.toShortString() + " gezündet");
   }

   void anchorSelfHit(float var1) {
      if (this.anchorAt == this.tick) {
         this.anchorSelfDamage += var1;
      }
   }

   void anchorHit(float var1) {
      if (this.anchorAt == this.tick) {
         this.anchorDamage += var1;
      }
   }

   void crystalPlaced(BlockPos var1) {
      this.event("Kristall auf " + var1.toShortString());
   }

   private boolean isSwordCrystalSequence() {
      return this.drill == Drill.HIT_CRYSTAL || this.drill == Drill.D_TAP || this.drill == Drill.TRIPLE_TAP || this.drill == Drill.TAP_ANCHOR;
   }

   void crystalBlast(ServerPlayer var1, LivingEntity var2, Vec3 var3, Vec3 var4, float var5, float var6, boolean var7) {
      this.crystalBlast(var1, var2, var3, var4, var5, var6, var7, !var2.onGround() || var2.getDeltaMovement().y > 0.02);
   }

   void crystalBlast(ServerPlayer var1, LivingEntity var2, Vec3 var3, Vec3 var4, float var5, float var6, boolean var7, boolean var8) {
      this.crystalBlast(var1, var2, var3, var4, var5, var6, var7, var8, Math.max(0, var2.invulnerableTime - 10));
   }

   void crystalBlast(ServerPlayer var1, LivingEntity var2, Vec3 var3, Vec3 var4, float var5, float var6, boolean var7, boolean var8, int var9) {
      this.crystalBlast(var1, var2, var3, var4, var5, var6, var7, var8, var9, false);
   }

   void crystalBlast(ServerPlayer var1, LivingEntity var2, Vec3 var3, Vec3 var4, float var5, float var6,
                     boolean var7, boolean var8, int var9, boolean poppedByThisBlast) {
      if (!this.done) {
         this.explosions++;
         this.event("Kristall " + this.explosions + ": Ziel " + decimal(var5) + " LP · selbst " + decimal(var6) + " LP");
         this.botHitOrigin = var4;
         this.displacementAt = this.tick;
         if (this.drill == Drill.LEDGE_DASH
            && var5 > 0.0F
            && this.startOwner != null
            && var1.getY() + 0.5 < this.startOwner.y
            && var1.getY() + 0.5 < var2.getY()) {
            this.finish(true, "Kristalltreffer von der tieferen Ebene");
         }

         if (this.isSwordCrystalSequence()) {
            boolean var10 = this.crystalHits == 0;
            String var11 = "Kristall " + (this.crystalHits + 1);
            int var12 = this.tick - (var10 ? this.swordAt : this.lastCrystal);
            if (this.swordAt == -100000) {
               this.timingFault(var11 + " zu früh: Nahkampftreffer 1 fehlt");
            } else if (this.needsAirSword()) {
               this.timingFault("Kristall 2 zu früh: Nahkampftreffer 2 in der Luft fehlt");
            } else if (var12 > this.options.windowTicks()) {
               this.timingFault(var11 + " zu spät: " + var12 + " Ticks seit dem letzten Treffer");
            } else if (!var10 || (this.drill == Drill.HIT_CRYSTAL ? var12 <= 10 : var12 < 10 && !var7)) {
               if (var8 && !this.openingLanded || !var10 && !this.options.airborneFollowups()) {
                  boolean var13 = this.drill == Drill.TRIPLE_TAP && this.crystalHits == 1 && this.airSwordStartedWindow && this.tick - this.airSwordAt < 10;
                  if (!var10 && !var7 && !var13) {
                     String var15 = var9 > 0 ? " · noch " + var9 + " Ticks warten" : " · Schadensfenster geschlossen";
                     this.timingFault(var11 + " zu früh nach " + var12 + " Ticks" + var15);
                  } else if (this.drill.requiresPopChain() && !poppedByThisBlast) {
                     this.finish(false, var11 + " hat kein Bot-Totem ausgelöst.");
                  } else if (var5 <= 0.0F && !poppedByThisBlast) {
                     this.timingFault(var11 + " ohne wirksamen Treffer · Deckung und Abstand prüfen");
                  } else {
                     Vec3 var14 = new Vec3(var3.x - var4.x, 0.0, var3.z - var4.z);
                     if (!var10 && this.options.pingPong() && this.firstCrystalSide != null && this.firstCrystalSide.dot(var14) >= 0.0) {
                        this.step("Kristall traf · Seitenwechsel fehlt");
                     } else {
                        this.clearTimingFault();
                        this.firstCrystalSide = var14;
                        this.crystalHits++;
                        this.lastCrystal = this.tick;
                        this.step(this.crystalHits + " wirksame Kristalltreffer");
                        if (this.drill == Drill.HIT_CRYSTAL
                           || this.drill == Drill.D_TAP && this.crystalHits == 2
                           || this.drill == Drill.TRIPLE_TAP && this.crystalHits == 3) {
                           this.finish(true, "Kristallfolge gelungen · " + this.crystalHits + " Treffer");
                        }
                     }
                  }
               } else {
                  if (this.drill.requiresPopChain()) {
                     this.finish(false, var11 + " traf nach dem Bodenkontakt.");
                  } else {
                     this.timingFault(var11 + " zu spät: Ziel bereits gelandet");
                  }
               }
            } else {
               this.timingFault("Erster Kristall zu spät nach dem Nahkampftreffer (" + var12 + " Ticks)");
            }
         }
      }
   }
}
