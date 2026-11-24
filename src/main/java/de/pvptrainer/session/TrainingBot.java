package de.pvptrainer.session;

import de.pvptrainer.arena.MacherAppearance;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.arena.TrainingTarget;
import de.pvptrainer.combat.MaceDuel;
import de.pvptrainer.combat.TrainingCrystal;
import de.pvptrainer.combat.TrainingTactics;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillAttempt;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.progress.HudPreferences;
import de.pvptrainer.progress.TrainingProgress;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData.DataValue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** Trainings-Gegner-Entitaet (Mannequin) mit Schild-, Treffer-, Totem- und Bewegungskontrolle. */
final class TrainingBot extends Mannequin implements MaceDuel.Glider, TrainingTactics.MeleeReach {
    private final TrainingSession session;
    private int reconsiderReachAt = -1;
    private double chosenReach;
    private float damageThisHit;
    private int immediateGuardUntil = -1;
    private int stunImpulseAt = -1;
    private long pendingWebClick;
    private int pendingWebTick;
    private int pendingWebDuration;
    private DamageSource shieldBlockedSource;
    private int shieldBlockedTick = -1;
    private boolean lastHealthVisible;
    private int lastShownHp = -1;
    private int lastShownMaxHp = -1;
    private int lastShownAbs = -1;

    TrainingBot(TrainingSession session, ServerLevel level) {
        super(level);
        this.session = session;
        this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.10000000149011612D);
        this.applyImplicitComponent(DataComponents.PROFILE, MacherAppearance.PROFILE);
        this.setCustomName(MacherAppearance.NAME);
        this.setCustomNameVisible(true);
        this.updateHealthIndicator();
    }

    void updateHealthIndicator() {
        boolean visible = HudPreferences.botHealth();
        int hp = visible ? Math.round(Math.max(0.0F, this.getHealth()) * 10.0F) : -1;
        int maxHp = visible ? Math.round(Math.max(0.0F, this.getMaxHealth()) * 10.0F) : -1;
        int abs = visible ? Math.round(Math.max(0.0F, this.getAbsorptionAmount()) * 10.0F) : -1;
        if (visible == this.lastHealthVisible && hp == this.lastShownHp && maxHp == this.lastShownMaxHp && abs == this.lastShownAbs) {
            return;
        }
        this.lastHealthVisible = visible;
        this.lastShownHp = hp;
        this.lastShownMaxHp = maxHp;
        this.lastShownAbs = abs;
        this.setCustomName(MacherAppearance.healthName(this.getHealth(), this.getMaxHealth(), this.getAbsorptionAmount(), visible));
    }

    @Override
    public float getSpeed() {
        return (float) this.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    @Override
    protected float getFlyingSpeed() {
        return this.isSprinting() ? 0.026F : 0.02F;
    }

    @Override
    public boolean canSprint() {
        return true;
    }

    @Override
    protected float getBlockSpeedFactor() {
        return this.isFallFlying() ? 1.0F : super.getBlockSpeedFactor();
    }

    @Override
    public void makeStuckInBlock(BlockState state, Vec3 motionMultiplier) {
        super.makeStuckInBlock(state, motionMultiplier);
        this.tryResetCurrentImpulseContext();
    }

    @Override
    protected void applyInput() {
        super.applyInput();
        // Match Vanilla LocalPlayer.modifyInput(): slow down while blocking/eating/drawing bow or crouching.
        if (this.isUsingItem() && !this.isPassenger()) {
            UseEffects effects = this.getUseItem().getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT);
            float mult = effects.speedMultiplier();
            this.xxa *= mult;
            this.zza *= mult;
            if (!effects.canSprint() && this.isSprinting()) {
                this.setSprinting(false);
            }
        }
        if (this.isCrouching() || this.isVisuallyCrawling()) {
            this.xxa *= 0.3F;
            this.zza *= 0.3F;
        }
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (this.isSwimming()) {
            double lookY = this.getLookAngle().y;
            double factor = lookY < -0.2 ? 0.085 : 0.06;
            if (lookY <= 0.0 || this.jumping
                || !this.level().getFluidState(BlockPos.containing(this.getX(), this.getY() + 1.0 - 0.1, this.getZ())).isEmpty()) {
                Vec3 vel = this.getDeltaMovement();
                this.setDeltaMovement(vel.add(0.0, (lookY - vel.y) * factor, 0.0));
            }
        }
        super.travel(travelVector);
    }

    @Override
    protected int getFireImmuneTicks() {
        return 20;
    }

    @Override
    public double meleeReach() {
        double cap = session.options.botReach();
        Drill drill = session.options.drill();
        if (drill == Drill.JUMP_RESET || drill == Drill.HIT_SELECT || drill == Drill.UPPERCUT
            || drill == Drill.PCRIT_BREAK || drill == Drill.MACE_COUNTER_DTAP) {
            return cap;
        }
        int tick = session.roundTick;
        if (tick >= reconsiderReachAt) {
            // Commit to one distance for several ticks instead of rerolling every range check.
            chosenReach = cap - getRandom().nextDouble() * Math.min(0.32, cap * 0.12);
            reconsiderReachAt = tick + 7 + getRandom().nextInt(6);
        }
        return chosenReach;
    }

    @Override
    public boolean launchGlide() {
        if (onGround() || !canGlide()) return false;
        setSharedFlag(FLAG_FALL_FLYING, true);
        return true;
    }

    void expireWebBreak() {
        if (pendingWebClick != 0 && session.roundTick - pendingWebTick > 4) {
            pendingWebClick = 0;
            session.attempt.finish(false, "Schild ohne Doppelklick gebrochen");
        }
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (this.isUsingItem() && this.getUseItem().has(DataComponents.BLOCKS_ATTACKS)) {
            byte flags = this.getEntityData().get(DATA_LIVING_ENTITY_FLAGS);
            this.sendUseFlags(player, (byte) (flags & -2));
            this.sendUseFlags(player, flags);
        }
    }

    private void sendUseFlags(ServerPlayer player, byte flags) {
        player.connection.send(new ClientboundSetEntityDataPacket(this.getId(), List.of(DataValue.create(DATA_LIVING_ENTITY_FLAGS, flags))));
    }

    private Vec3 constrainedMotion(Vec3 velocity) {
        if (session.options != null && session.options.drill() == Drill.WIND_STUN_SLAM) {
            return session.maceCenter == null ? velocity : new Vec3(0, velocity.y, 0);
        }
        if (session.waitingForStart || session.maceCenter != null && session.options.slamScene() != DrillOptions.SlamScene.PAIRED_FALL) {
            return Vec3.ZERO;
        }
        if (session.options == null || session.options.slamScene() == DrillOptions.SlamScene.FREE) {
            return velocity;
        }
        return session.options.slamScene() == DrillOptions.SlamScene.PAIRED_FALL && !session.waitingForStart
            ? new Vec3(0.0, Math.min(0.0, velocity.y), 0.0)
            : Vec3.ZERO;
    }

    @Override
    public void move(MoverType moverType, Vec3 movement) {
        super.move(moverType, this.constrainedMotion(movement));
        if ((session.waitingForStart && (session.options == null || session.options.drill() != Drill.WIND_STUN_SLAM))
            || session.maceCenter != null
                && session.options.drill() != Drill.WIND_STUN_SLAM
                && session.options.slamScene() != DrillOptions.SlamScene.PAIRED_FALL) {
            // A held or fixed bot never descends into the floor, so ordinary movement resets onGround to false every tick.
            setOnGround(!level().noBlockCollision(this, getBoundingBox().move(0, -0.01, 0)));
        }
    }

    @Override
    public void setDeltaMovement(Vec3 velocity) {
        super.setDeltaMovement(this.constrainedMotion(velocity));
    }

    @Override
    public void knockback(double strength, double x, double z, DamageSource source, float damage, boolean force) {
        boolean webMelee = session.options != null && session.options.drill() == Drill.STUN_WEB
            && source != null && source.is(DamageTypes.PLAYER_ATTACK) && source.getDirectEntity() == session.owner;
        if (webMelee && this.stunImpulseAt == session.roundTick) return;
        boolean shieldBlocked = source != null && source == this.shieldBlockedSource && this.shieldBlockedTick == session.roundTick;
        if (shieldBlocked && damage <= 0.0F) {
            // Exact Vanilla ServerPlayer parity: when a shield blocks 100% of damage (remaining == 0),
            // LivingEntity.hurtServer() skips markHurt() so no motion packet is sent and the player takes zero knockback.
            return;
        }
        boolean keepGrounded = webMelee && this.onGround();
        double previousY = this.getDeltaMovement().y;
        super.knockback(strength, x, z, source, damage, force);
        if (keepGrounded) {
            Vec3 after = this.getDeltaMovement();
            this.setDeltaMovement(new Vec3(after.x, Math.min(previousY, after.y), after.z));
        }
    }

    private boolean isRefillTarget() {
        return session.options != null && session.options.drill().totemDefense() && session.refillRecoverySpot != null;
    }

    void recoverRefillTarget() {
        this.snapTo(session.refillRecoverySpot, this.getYRot(), 0.0F);
        this.setDeltaMovement(Vec3.ZERO);
        this.resetFallDistance();
        this.clearFire();
        this.setHealth(this.getMaxHealth());
        this.updateHealthIndicator();
        this.hurtMarked = true;
    }

    @Override
    protected void onBelowWorld() {
        if (this.isRefillTarget()) {
            this.recoverRefillTarget();
        } else {
            super.onBelowWorld();
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (session.waitingForStart) {
            return false;
        }
        if (session.options != null && session.options.drill() == Drill.SHIELD_TIMING
            && source.getDirectEntity() == session.owner && session.owner != null
            && session.owner.getMainHandItem().is(ItemTags.AXES)) {
            if (session.attempt != null) session.attempt.axeNotAllowedInShieldTiming();
            return false;
        }
        boolean scalesWithDifficulty = source.scalesWithDifficulty()
            && !(source.type().scaling() == DamageScaling.WHEN_CAUSED_BY_LIVING_NON_PLAYER
                && TrainingTarget.matches(source.getEntity()));
        if (scalesWithDifficulty) {
            amount = TrainingArena.scalePlayerDamage(amount, TrainingArena.resolveDamageDifficulty(level), true, TrainingArena.isArena(level));
            if (amount == 0.0F) {
                return false;
            }
        }

        session.equipment.protectPassiveTarget(this);
        ItemStack mainHand = this.getMainHandItem();
        ItemStack offHand = this.getOffhandItem();
        int mainCount = mainHand.getCount();
        int offCount = offHand.getCount();
        boolean mainTotem = mainHand.has(DataComponents.DEATH_PROTECTION);
        boolean offTotem = offHand.has(DataComponents.DEATH_PROTECTION);
        boolean wasBlocking = this.isBlocking();
        BlocksAttacks shield = this.getUseItem().get(DataComponents.BLOCKS_ATTACKS);
        int blockDelayRemaining = this.isUsingItem() && shield != null ? shield.blockDelayTicks() - this.getTicksUsingItem() : 0;
        boolean airborne = !this.onGround() || this.getDeltaMovement().y > 0.02;
        boolean hurtWindowOpen = this.invulnerableTime <= 10;

        boolean confirmWeb = pendingWebClick != 0 && session.roundTick - pendingWebTick <= 4
            && source.is(DamageTypes.PLAYER_ATTACK) && source.getDirectEntity() == session.owner
            && session.owner.getMainHandItem().is(ItemTags.AXES) && session.webClickOnBot == pendingWebClick + 1;
        boolean maceAttack = source.getDirectEntity() == session.owner
            && session.owner.getMainHandItem().is(Items.MACE)
            && (source.is(DamageTypes.MACE_SMASH) || source.is(DamageTypes.PLAYER_ATTACK));
        boolean realMaceSmash = source.getDirectEntity() == session.owner
            && session.owner.getMainHandItem().is(Items.MACE)
            && source.is(DamageTypes.MACE_SMASH);
        boolean isStunSlamDrill = session.options.drill() == Drill.STUN_SLAM
            || session.options.drill() == Drill.ELY_STUN_SLAM;
        boolean drillSmash = session.options.drill().centeredMaceDrill() && maceAttack
            && (source.is(DamageTypes.MACE_SMASH)
                || session.options.drill() == Drill.WIND_STUN_SLAM
                    && session.windTarget.isAirborne(this)
                    && session.windTarget.isAirborneAxeStun()
                    && session.attempt.shieldWindowOpen()
                    && !wasBlocking);
        boolean validSmash = drillSmash && (session.options.drill() == Drill.LOW_WEB_SMASH
            || session.windTarget.isAirborne(this) && session.windTarget.isAirborneAxeStun()
            && session.attempt.shieldWindowOpen() && !wasBlocking);
        boolean webContact = session.options.drill() == Drill.LOW_WEB_SMASH && maceAttack;
        // Test before damage, totem handling or Wind Burst changes the attacker's fall state.
        boolean webSmash = webContact && (drillSmash || MaceItem.canSmashAttack(session.owner));
        boolean stunSlamMaceOpen = isStunSlamDrill && realMaceSmash
            && !wasBlocking && session.attempt != null && session.attempt.shieldWindowOpen();
        if ((webSmash || stunSlamMaceOpen || validSmash) && invulnerableTime > 10) invulnerableTime = 10;

        double attackerFall = (session.owner != null && maceAttack) ? Math.max(0.0, session.owner.fallDistance) : 0.0;
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt && source.getEntity() == session.owner) {
            if (realMaceSmash || webSmash) {
                session.metrics.recordMaceSmash(attackerFall, this.damageThisHit);
            } else if (source.is(DamageTypes.PLAYER_ATTACK) && source.getDirectEntity() == session.owner) {
                session.metrics.recordMeleeHit(session.swingCharge);
            }
        }
        if (webContact) {
            if (webSmash || !session.cobwebFlight.isFlightJudged()) {
                session.attempt.finish(webSmash, webSmash ? "Smash unter der Netzkante gelandet!" : "Zu wenig Fallhöhe");
                session.storeMaceRepetition();
            }
        } else if (drillSmash && hurt && !session.attempt.done() && !session.windTarget.isScoredWindFlight()) {
            if (validSmash && session.options.drill() == Drill.WIND_STUN_SLAM) {
                session.metrics.recordStunSlam();
            }
            session.attempt.finish(validSmash, validSmash
                ? "Axt-Stun und Smash im selben Flug!"
                : "Brich das Schild in der Luft und triff den Bot vor seiner Landung.");
            session.storeMaceRepetition();
        }
        if (confirmWeb && !session.attempt.done()) {
            pendingWebClick = 0;
            int remaining = Math.max(1, pendingWebDuration - (session.roundTick - pendingWebTick));
            session.metrics.recordShieldStun();
            session.attempt.shieldStun(remaining);
            session.armSecondarySlam(remaining);
            stunImpulseAt = session.roundTick;
            if (onGround()) super.knockback(0.4, session.owner.getX() - getX(), session.owner.getZ() - getZ(), source, amount, false);
        }

        if (session.attempt == null || session.owner == null) {
            return hurt;
        }

        if (isStunSlamDrill && source.getEntity() == session.owner) {
            if (session.owner.getMainHandItem().is(ItemTags.AXES)) {
                session.attempt.axeContact(wasBlocking, blockDelayRemaining);
            }
            if (realMaceSmash && hurt) {
                session.attempt.beginStunSlamLaunch(session.owner, wasBlocking);
            } else if (session.owner.getMainHandItem().is(Items.MACE) && (!hurt || !realMaceSmash)) {
                session.attempt.maceContact(session.owner.fallDistance, session.owner.onGround(), realMaceSmash);
            }
        }

        if (hurt && source.getEntity() == session.owner) {
            session.hits++;
            if (session.secondarySlam != null && realMaceSmash) {
                session.secondarySlam.smash(session.owner, wasBlocking);
                if (session.secondarySlam.won()) {
                    session.metrics.recordStunSlam();
                    session.finishSecondarySlam();
                }
            }
            if (!drillSmash && !isStunSlamDrill && source.is(DamageTypes.MACE_SMASH) && !session.airStartLanded()) {
                session.attempt.smash(session.owner, wasBlocking);
            }
        }

        if (source.is(DamageTypes.PLAYER_ATTACK)
            && source.getDirectEntity() == session.owner
            && session.comboWeapon(session.owner)
            && (hurt
                || session.directComboClick == session.roundTick
                    && !hurtWindowOpen
                    && !wasBlocking
                    && amount > 0.0F
                    && (session.attempt.needsAirSword() || session.attempt.acceptsAirComboTap()))) {
            session.attempt.swordContact(session.owner, this, hurt ? this.damageThisHit : 0.0F, session.swingCharge, airborne, hurt, hurtWindowOpen);
            session.directComboClick = -1;
        }

        InteractionHand poppedHand = mainTotem && mainHand.getCount() < mainCount
            ? InteractionHand.MAIN_HAND
            : (offTotem && offHand.getCount() < offCount ? InteractionHand.OFF_HAND : null);
        if (poppedHand != null && this.isAlive()) {
            session.pops++;
            session.metrics.recordBotPop();
            session.attempt.botPop();
            if (source.is(DamageTypes.BAD_RESPAWN_POINT)) {
                session.metrics.recordAnchorPop();
                session.attempt.anchorPop();
            }
            if (session.options.drill() == Drill.TOTEM_POPS) {
                session.recordRepetition();
            } else {
                TrainingProgress.technique(session.historyKey, session.settings, session.options, session.ownerId, Drill.TOTEM_POPS, true,
                    session.roundTick - session.secondaryPopAt, "Bot-Totem ausgelöst");
                session.secondaryPopAt = session.roundTick;
            }
            session.equipment.popped(this, poppedHand, session.roundTick);
            if (session.options.drill() == Drill.SHIELD_TRIPLE_TAP && !session.equipment.isPostPopShieldActive()) {
                if (session.equipment.activatePostPopShield(this)) {
                    BlocksAttacks postPopShield = this.getItemInHand(session.equipment.shieldHand).get(DataComponents.BLOCKS_ATTACKS);
                    if (postPopShield != null) {
                        this.pauseShield(0, postPopShield, true);
                    }
                }
            }
            session.resetAfterPop = session.options.resetOnPop();
            this.updateHealthIndicator();
        }

        return hurt;
    }

    @Override
    public void startUsingItem(InteractionHand hand) {
        if (session.roundTick >= session.shieldReadyAt || !this.getItemInHand(hand).has(DataComponents.BLOCKS_ATTACKS)) {
            super.startUsingItem(hand);
        }
    }

    void pauseShield(int ticks, BlocksAttacks shield, boolean exactWindow) {
        int readyAt = session.roundTick + ticks;
        session.shieldReadyAt = readyAt;
        if (exactWindow) {
            // Count until coverage resumes, including the usual shield-raise animation.
            this.immediateGuardUntil = readyAt + shield.blockDelayTicks();
            if (ticks == 0) this.startUsingItem(session.equipment.shieldHand);
        }
    }

    @Override
    public ItemStack getItemBlockingWith() {
        return session.options != null
            && session.roundTick <= this.immediateGuardUntil
            && this.isUsingItem()
            && this.getUseItem().has(DataComponents.BLOCKS_ATTACKS)
            ? this.getUseItem()
            : super.getItemBlockingWith();
    }

    @Override
    protected void actuallyHurt(ServerLevel level, DamageSource source, float amount) {
        float hpBefore = this.getHealth();
        float absBefore = this.getAbsorptionAmount();
        float before = hpBefore + absBefore;
        super.actuallyHurt(level, source, amount);
        this.damageThisHit = Math.max(0.0F, before - DrillAttempt.health(this));
        if (this.damageThisHit > 0.0F) {
            session.metrics.recordDamageDealt(this.damageThisHit);
        }
        if (TrainingResources.zeroDamageFor(this)) {
            boolean allowPop = session.options != null
                && (session.options.drill().requiresPopChain() || session.options.drill() == Drill.TOTEM_POPS)
                && this.isDeadOrDying()
                && (getMainHandItem().has(DataComponents.DEATH_PROTECTION) || getOffhandItem().has(DataComponents.DEATH_PROTECTION));
            if (!allowPop) {
                this.setHealth(hpBefore);
                this.setAbsorptionAmount(absBefore);
            }
        }
        // Keep the training target available even in kits without death protection.
        if (session.options.drill().centeredMaceDrill() && getHealth() <= 0
            && !getMainHandItem().has(DataComponents.DEATH_PROTECTION) && !getOffhandItem().has(DataComponents.DEATH_PROTECTION)) {
            setHealth(getMaxHealth());
        }
        this.updateHealthIndicator();
        if (damageThisHit > 0 && session.options.drill() == Drill.DUEL
            && source.is(DamageTypes.PLAYER_ATTACK) && source.getDirectEntity() == session.owner) {
            if (session.settings.mode() == TrainingSettings.Mode.SWORD) {
                session.tactics.swordDuel.incomingMelee(session.roundTick);
            } else if (session.settings.mode() == TrainingSettings.Mode.UHC) {
                session.tactics.onBotDamaged(session.roundTick);
            }
        }
        if (source.getDirectEntity() instanceof TrainingCrystal) {
            session.lastBotDamage = session.lastBotDamage + this.damageThisHit;
        }
        if (session.attempt != null && source.is(DamageTypes.BAD_RESPAWN_POINT)) {
            session.attempt.anchorHit(this.damageThisHit);
        }
    }

    @Override
    public float applyItemBlocking(ServerLevel level, DamageSource source, float amount) {
        InteractionHand hand = this.getUsedItemHand();
        ItemStack shieldStack = this.getItemBlockingWith();
        BlocksAttacks shield = shieldStack == null ? null : shieldStack.get(DataComponents.BLOCKS_ATTACKS);
        // Falling straight down from steep stun-slam heights can land near dx=0, dz=0 where the horizontal
        // shield cone flickers 180°. Align head yaw to the falling attacker for frontal axe hits in slam drills.
        if (shield != null
            && session.options != null
            && (session.options.drill().slamOptions() || session.options.drill() == Drill.ELY_STUN_SLAM)
            && source.getDirectEntity() == session.owner
            && session.owner != null
            && session.owner.getMainHandItem().is(ItemTags.AXES)
            && session.owner.getY() > this.getY() + 0.5) {
            double dx = session.owner.getX() - this.getX();
            double dz = session.owner.getZ() - this.getZ();
            if (dx * dx + dz * dz < 1.44 && dx * dx + dz * dz > 1.0e-8) {
                float faceYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
                this.setYRot(faceYaw);
                this.setYHeadRot(faceYaw);
                this.setYBodyRot(faceYaw);
            }
        }
        Vec3 velBeforeBlock = this.getDeltaMovement();
        float blocked = super.applyItemBlocking(level, source, amount);
        if (blocked >= amount && blocked > 0.0F) {
            this.shieldBlockedSource = source;
            this.shieldBlockedTick = session.roundTick;
            // Exact Vanilla ServerPlayer parity: on a 100%-blocked hit (remaining == 0), LivingEntity.hurtServer()
            // never calls markHurt(), so a real ServerPlayer receives no velocity packet and keeps its prior velocity.
            if (this.stunImpulseAt != session.roundTick) {
                this.setDeltaMovement(velBeforeBlock);
            }
        }
        if (shield != null && blocked > 0.0F && session.attempt != null && session.equipment != null) {
            int damageBefore = shieldStack.getDamageValue();
            shieldStack.hurtAndBreak(shield.itemDamage().apply(blocked), this, hand);
            boolean broken = shieldStack.isEmpty();
            if (source.getEntity() == session.owner) {
                session.attempt.shieldBlocked(blocked);
                if (session.options.drill() == Drill.SHIELD) {
                    session.recordRepetition();
                }
                if (broken) {
                    session.metrics.recordShieldBreak();
                    session.attempt.shieldBreak();
                    if (session.options.drill() == Drill.STUN_WEB && this.stunImpulseAt != session.roundTick) {
                        session.attempt.finish(false, "Schild ohne gültigen Stun verbraucht");
                    }
                } else if (shieldStack.getDamageValue() > damageBefore) {
                    session.attempt.event("Schild −" + (shieldStack.getDamageValue() - damageBefore) + " Haltbarkeit");
                }
            }
            if (broken) {
                this.stopUsingItem();
                session.equipment.broken(this);
                int pause = session.options.shieldBreakTicks(session.settings.mode());
                BlocksAttacks replacement = this.getItemInHand(session.equipment.shieldHand).get(DataComponents.BLOCKS_ATTACKS);
                if (pause >= 0 && replacement != null) this.pauseShield(pause, replacement, true);
            }
        }
        return blocked;
    }

    @Override
    protected void blockUsingItem(ServerLevel level, LivingEntity attacker, DamageSource source, float amount) {
        super.blockUsingItem(level, attacker, source, amount);
        ItemStack shieldStack = this.getItemBlockingWith();
        BlocksAttacks shield = shieldStack == null ? null : shieldStack.get(DataComponents.BLOCKS_ATTACKS);
        if (shield == null || session.attempt == null) return;

        float disableSeconds = attacker.getSecondsToDisableBlocking();
        if (session.options.drill() == Drill.SHIELD_TIMING && attacker == session.owner && disableSeconds > 0) {
            session.attempt.axeNotAllowedInShieldTiming();
            return;
        }
        if (session.options.drill() == Drill.STUN_WEB) {
            if (attacker != session.owner || source.getDirectEntity() != attacker || !source.is(DamageTypes.PLAYER_ATTACK)
                || !(attacker.getMainHandItem().is(ItemTags.AXES) || attacker.getMainHandItem().is(ItemTags.SWORDS))) {
                return;
            }
            float validStun = session.attempt.doubleClickDisable(
                session.webClickOnBot,
                attacker.getMainHandItem().is(ItemTags.AXES) ? disableSeconds : 0
            );
            if (disableSeconds > 0 && validStun == 0) {
                shield.disable(level, this, disableSeconds, shieldStack);
                int duration = Math.round(20 * disableSeconds * shield.disableCooldownScale());
                this.pauseShield(duration, shield, false);
                this.pendingWebClick = session.webClickOnBot;
                this.pendingWebTick = session.roundTick;
                this.pendingWebDuration = duration;
                return;
            }
            disableSeconds = validStun;
        }

        int disableTicks = Math.round(20.0F * disableSeconds * shield.disableCooldownScale());
        if (disableTicks > 0) {
            shield.disable(level, this, disableSeconds, shieldStack);
            DrillOptions options = session.options;
            int configured = options.shieldBreakTicks(session.settings.mode());
            int pause = configured >= 0 ? configured : (options.instantReblock() ? 0 : disableTicks);
            this.pauseShield(pause, shield, configured >= 0 || options.instantReblock() && options.holdShield());

            if (attacker == session.owner) {
                if (options.drill() == Drill.CORNER_WEB_DTAP) {
                    session.attempt.confirmCornerWeb(DrillAttempt.inWeb(this));
                }
                int raiseTicks = (options.drill() == Drill.STUN_SLAM || options.drill() == Drill.ELY_STUN_SLAM)
                    && configured < 0 && !options.instantReblock()
                    ? shield.blockDelayTicks() : 0;
                session.metrics.recordShieldStun();
                session.attempt.shieldStun(pause + raiseTicks);
                if (options.drill() == Drill.WIND_STUN_SLAM && !session.windTarget.isScoredWindFlight()
                    && session.windTarget.isAirborne(this) && pause > 0
                    && attacker.getMainHandItem().is(ItemTags.AXES) && source.getDirectEntity() == session.owner) {
                    session.windTarget.markAirborneAxeStun();
                }
                if (options.botAutoHit() && pause > 0) {
                    session.scheduleBotAutoHit();
                }
                if (options.drill() == Drill.DUEL && session.settings.mode() == TrainingSettings.Mode.UHC && pause > 0) {
                    session.tactics.onBotShieldDisabled(session.roundTick, session.settings);
                }
                if (options.drill() != Drill.STUN_SLAM
                    && options.drill() != Drill.ELY_STUN_SLAM
                    && options.drill() != Drill.STUN_WEB
                    && options.drill() != Drill.MOMENTUM_BREAK
                    && options.drill() != Drill.WIND_STUN_SLAM
                    && options.drill() != Drill.CORNER_WEB_DTAP
                    && options.drill() != Drill.SHIELD_TRIPLE_TAP
                    && attacker.getMainHandItem().is(ItemTags.AXES) && source.getDirectEntity() == session.owner && pause > 0) {
                    session.armSecondarySlam(pause);
                }
                if (session.options.drill() == Drill.STUN_WEB && this.onGround()) {
                    this.stunImpulseAt = session.roundTick;
                    super.knockback(0.4, attacker.getX() - this.getX(), attacker.getZ() - this.getZ(), source, amount, false);
                }
            }
        }
    }

    @Override
    public void hurtArmor(DamageSource source, float amount) {
        this.doHurtEquipment(source, amount, new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET});
    }

    @Override
    public void hurtHelmet(DamageSource source, float amount) {
        this.doHurtEquipment(source, amount, new EquipmentSlot[]{EquipmentSlot.HEAD});
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean saveAsPassenger(ValueOutput output) {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean checkDimension) {
        return false;
    }

    @Override
    protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {
    }
}
