package de.pvptrainer.combat;
import de.pvptrainer.analytics.BotTelemetry;
import de.pvptrainer.drill.DrillAttempt;
import de.pvptrainer.TrainerClient;
import de.pvptrainer.session.TrainingSession;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level.ExplosionInteraction;
import net.minecraft.world.phys.Vec3;

public final class TrainingCrystal extends EndCrystal {
   private final ServerLevel world;
   private final ServerPlayer owner;
   private final LivingEntity bot;

   TrainingCrystal(ServerLevel var1, Vec3 var2, ServerPlayer var3, LivingEntity var4) {
      super(var1, var2.x, var2.y, var2.z);
      this.world = var1;
      this.owner = var3;
      this.bot = var4;
   }

   public void tick() {
      this.time++;
   }

   public boolean shouldBeSaved() {
      return false;
   }

   public boolean belongsTo(ServerPlayer var1, LivingEntity var2) {
      return this.owner == var1 && this.bot == var2;
   }

   public boolean hitInTraining(DamageSource var1) {
      if (var1.getEntity() == this.owner && !this.isRemoved()) {
         this.detonate(this.owner);
         return true;
      } else {
         return false;
      }
   }

   void detonate(LivingEntity var1) {
      this.detonate(var1, true);
   }

   boolean detonate(LivingEntity var1, boolean var2) {
      if (this.isRemoved()) {
         return false;
      }

      Vec3 var3 = this.position();
      this.discard();
      if (this.bot.isAlive() && this.owner.isAlive() && this.owner.level() == this.world && this.bot.level() == this.world) {
         if (var1 == this.bot) BotTelemetry.action(this.world, this.bot, "CRYSTAL");
         TrainingSession var4 = TrainerClient.session(this.world.getServer());
         final boolean var5 = var1 == this.bot && var4 != null && var4.defendingAgainst(this);
         boolean var6 = this.owner.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
         boolean var7 = this.owner.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
         int var8 = var5 ? var4.ownerPops() : 0;
         ExplosionDamageCalculator var9 = new ExplosionDamageCalculator() {
            public boolean shouldDamageEntity(Explosion var1, Entity var2x) {
               return var2x == TrainingCrystal.this.owner || var2x == TrainingCrystal.this.bot;
            }

            public float getKnockbackMultiplier(Entity var1) {
               return var1 == TrainingCrystal.this.owner || var1 == TrainingCrystal.this.bot ? 1.0F : 0.0F;
            }
         };
         boolean var10 = var4 != null && (var5 || var1 == this.owner);
         boolean var11 = var10 && var4.beginCrystalBlast(this);
         Vec3 var12 = this.bot.position();
         float var13 = DrillAttempt.health(this.owner);
         this.world
            .explode(
               this,
               this.damageSources().explosion(this, var1),
               var9,
               var3,
               6.0F,
               false,
               ExplosionInteraction.NONE
            );
         if (var5) {
            return var4.endDefenseBlast(var6, var7, var8, var2);
         }

         if (var10) {
            var4.endCrystalBlast(this, var3, var12, Math.max(0.0F, var13 - DrillAttempt.health(this.owner)), var11);
         }

         return false;
      } else {
         return false;
      }
   }
}
