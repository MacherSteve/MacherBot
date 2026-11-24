package de.pvptrainer.drill;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;

/**
 * Exact Vanilla Minecraft 26.2 projectile interception solver for WindCharge-on-EnderPearl catches.
 *
 * <p>Matches:
 * <ul>
 *   <li>{@code ThrowableProjectile.tick()}: gravity {@code -0.03} then drag {@code 0.99} per tick</li>
 *   <li>{@code WindChargeItem.use()} / {@code Projectile.shootFromRotation()}: spawn at player eye Y, speed
 *       {@code 1.5} b/t + inherited {@code knownMovement} (Y zeroed on ground), {@code inaccuracy = 1.0F},
 *       zero drag/gravity</li>
 *   <li>{@code EntityTickList} insertion order + {@code ProjectileUtil.computeMargin(tickCount)} +
 *       {@code AABB.clip(p0, p1)} entry-face collision where the older pearl sweeps {@code [p(k-1), p(k)]}
 *       against the WindCharge box at {@code w(k-1)}</li>
 * </ul>
 * Optimizes aim direction toward the 3D centroid of the hitting cone under Vanilla {@code inaccuracy = 1.0F}
 * spread and reports real estimated hit confidence in {@code [0, 1]}.
 */
public final class PearlInterceptSolver {
    public static final double WIND_SPEED = 1.5;
    public static final double PEARL_GRAVITY = 0.03;
    public static final double PEARL_DRAG = 0.99;
    public static final double CHARGE_HALF_WIDTH = 0.15625;
    public static final double CHARGE_HEIGHT = 0.3125;
    public static final double HIT_MARGIN = 0.3;
    public static final int MAX_TICKS = 60;
    public static final double READY_CONFIDENCE = 0.78;

    // 37-point 3D velocity-noise stencil matching Projectile.getMovementToShoot(dir + triangle(0, 0.0172275)) * 1.5
    private static final double[][] SPREAD_SAMPLES = buildSpreadSamples();
    private static final double SPREAD_MAX_WEIGHT = computeMaxWeight();

    public enum Status {
        /** High-confidence discrete MC 26.2 hit confirmed (even under Vanilla inaccuracy = 1.0F). */
        READY,
        /** Tight/far discrete hit (lower confidence) or temporary apex phase gap (wait 1–2 ticks). */
        MARGINAL,
        /** Pearl hits floor/boundary before any WindCharge can reach it. */
        UNREACHABLE
    }

    public record Solution(
        Vec3 aimPoint,
        Vec3 aimDirection,
        int interceptTicks,
        double distance,
        double hitConfidence,
        Status status,
        boolean discreteHit
    ) {}

    private PearlInterceptSolver() {}

    public static Solution solve(Vec3 pearlPos, Vec3 pearlVel, Vec3 launchOrigin, Vec3 playerVel, double floorY) {
        return solve(pearlPos, pearlVel, launchOrigin, playerVel, floorY, 8);
    }

    /**
     * Computes the highest-confidence WindCharge aim vector for the exact server-side pearl state.
     *
     * @param pearlPos       exact pearl position (server tick end state)
     * @param pearlVel       exact pearl velocity (server tick end state)
     * @param launchOrigin   WindCharge spawn position {@code (player.x, player.eyeY, player.z)}
     * @param playerVel      inherited shooter velocity {@code (known.x, onGround ? 0 : known.y, known.z)}
     * @param floorY         arena floor top Y (e.g. {@code ForgeArena.FLOOR + 1.0}); use {@code Double.NEGATIVE_INFINITY} for unbounded
     * @param pearlTickCount current {@code pearl.tickCount} for exact {@code computeMargin} calculation
     */
    public static Solution solve(
        Vec3 pearlPos,
        Vec3 pearlVel,
        Vec3 launchOrigin,
        Vec3 playerVel,
        double floorY,
        int pearlTickCount
    ) {
        Vec3[] pos = new Vec3[MAX_TICKS + 1];
        int maxK = simulatePearl(pearlPos, pearlVel, floorY, pos);
        if (maxK == 0) {
            Vec3 fallbackDir = safeNormalize(pearlPos.subtract(launchOrigin));
            return new Solution(pearlPos, fallbackDir, 1, pearlPos.distanceTo(launchOrigin), 0.0, Status.UNREACHABLE, false);
        }

        // Tick 1 special case: pearl segment [pos[0], pos[1]] already enters the WindCharge spawn box.
        if (pos[0].y > floorY + 0.15 && windChargeBox(launchOrigin, marginAt(pearlTickCount + 1)).clip(pos[0], pos[1]).isPresent()) {
            Vec3 mid = pos[0].lerp(pos[1], 0.5);
            Vec3 dir = safeNormalize(mid.subtract(launchOrigin));
            return new Solution(mid, dir, 1, mid.distanceTo(launchOrigin), 1.0, Status.READY, true);
        }

        // 1. Search candidate discrete intercept points across all sphere-crossing ticks k >= 2
        Vec3 bestDir = null;
        Vec3 bestPoint = null;
        double bestConf = 0.0;
        double bestCenterErr = Double.POSITIVE_INFINITY;
        int bestTick = -1;

        for (int k = 2; k <= maxK; k++) {
            double tau = k - 1;
            double reach = WIND_SPEED * tau;
            Vec3 originAtTau = launchOrigin.add(playerVel.scale(tau));
            Vec3 p0 = pos[k - 1].subtract(0.0, CHARGE_HEIGHT * 0.5, 0.0);
            Vec3 p1 = pos[k].subtract(0.0, CHARGE_HEIGHT * 0.5, 0.0);
            double d0 = p0.distanceTo(originAtTau);
            double d1 = p1.distanceTo(originAtTau);
            if (Math.min(d0, d1) > reach + 2.0 || Math.max(d0, d1) < reach - 2.0) {
                continue;
            }
            for (double u : new double[]{0.15, 0.35, 0.55, 0.75, 0.95}) {
                Vec3 segPoint = pos[k - 1].lerp(pos[k], u);
                if (segPoint.y <= floorY + 0.15) continue;
                Vec3 targetOrigin = segPoint.subtract(0.0, CHARGE_HEIGHT * 0.5, 0.0);
                Vec3 dir = safeNormalize(targetOrigin.subtract(originAtTau));
                HitResultCheck center = simulateDiscreteHit(pos, maxK, launchOrigin, dir.scale(WIND_SPEED).add(playerVel), floorY, pearlTickCount);
                if (!center.hit) continue;
                double conf = evaluateSpreadConfidence(pos, maxK, launchOrigin, dir, playerVel, floorY, pearlTickCount);
                if (conf > bestConf + 1.0e-4
                    || (Math.abs(conf - bestConf) <= 1.0e-4 && center.centerError < bestCenterErr)) {
                    bestConf = conf;
                    bestCenterErr = center.centerError;
                    bestDir = dir;
                    bestPoint = segPoint;
                    bestTick = center.tick;
                }
            }
        }

        // 2. Refine best discrete aim to the exact 2D angular centroid of the hitting cone (5x5 angular patch)
        if (bestDir != null) {
            Vec3 right = perpendicularBasis(bestDir);
            Vec3 up = bestDir.cross(right).normalize();
            double sumX = 0.0, sumY = 0.0, sumZ = 0.0;
            int hitCount = 0;
            double step = 0.0055; // ~0.31 deg per grid step
            for (int ia = -2; ia <= 2; ia++) {
                for (int ib = -2; ib <= 2; ib++) {
                    double da = ia * step;
                    double db = ib * step;
                    Vec3 cand = safeNormalize(bestDir.add(right.scale(da)).add(up.scale(db)));
                    if (simulateDiscreteHit(pos, maxK, launchOrigin, cand.scale(WIND_SPEED).add(playerVel), floorY, pearlTickCount).hit) {
                        sumX += cand.x;
                        sumY += cand.y;
                        sumZ += cand.z;
                        hitCount++;
                    }
                }
            }
            if (hitCount > 0) {
                Vec3 centroid = safeNormalize(new Vec3(sumX, sumY, sumZ));
                HitResultCheck cCheck = simulateDiscreteHit(pos, maxK, launchOrigin, centroid.scale(WIND_SPEED).add(playerVel), floorY, pearlTickCount);
                if (cCheck.hit) {
                    double cConf = evaluateSpreadConfidence(pos, maxK, launchOrigin, centroid, playerVel, floorY, pearlTickCount);
                    if (cConf >= bestConf - 1.0e-4) {
                        bestConf = cConf;
                        bestDir = centroid;
                        bestTick = cCheck.tick;
                        double dist = WIND_SPEED * Math.max(1, bestTick - 1);
                        bestPoint = launchOrigin.add(playerVel.scale(Math.max(1, bestTick - 1)))
                            .add(bestDir.scale(dist))
                            .add(0.0, CHARGE_HEIGHT * 0.5, 0.0);
                    }
                }
            }
            Status status = bestConf >= READY_CONFIDENCE ? Status.READY : Status.MARGINAL;
            return new Solution(bestPoint, bestDir, bestTick, bestPoint.distanceTo(launchOrigin), bestConf, status, true);
        }

        // 3. No discrete hit on this exact tick — check if continuous crossing exists (temporary apex phase gap) vs unreachable.
        int crossK = -1;
        double prevDiff = Double.NaN;
        double currDiff = Double.NaN;
        for (int k = 2; k <= maxK; k++) {
            double tau = k - 1;
            Vec3 target = targetOriginForTick(pos, k);
            double diff = target.subtract(launchOrigin).scale(1.0 / tau).subtract(playerVel).length() - WIND_SPEED;
            if (diff <= 0.0) {
                crossK = k;
                currDiff = diff;
                break;
            }
            prevDiff = diff;
        }
        if (crossK < 0) {
            Vec3 endPoint = pos[maxK];
            Vec3 dir = safeNormalize(endPoint.subtract(launchOrigin).subtract(playerVel.scale(Math.max(1, maxK - 1))));
            return new Solution(endPoint, dir, maxK, endPoint.distanceTo(launchOrigin), 0.0, Status.UNREACHABLE, false);
        }

        Vec3 contAimPoint;
        Vec3 contDir;
        if (crossK >= 3 && Double.isFinite(prevDiff) && prevDiff > 0.0) {
            double denom = prevDiff - currDiff;
            double frac = denom > 1.0e-9 ? Math.clamp(prevDiff / denom, 0.0, 1.0) : 1.0;
            double tauStar = (crossK - 2) + frac;
            Vec3 targetStar = targetOriginForTick(pos, crossK - 1).lerp(targetOriginForTick(pos, crossK), frac);
            contAimPoint = targetStar.add(0.0, CHARGE_HEIGHT * 0.5, 0.0);
            contDir = safeNormalize(targetStar.subtract(launchOrigin).scale(1.0 / tauStar).subtract(playerVel));
        } else {
            Vec3 target = targetOriginForTick(pos, crossK);
            contAimPoint = target.add(0.0, CHARGE_HEIGHT * 0.5, 0.0);
            contDir = safeNormalize(target.subtract(launchOrigin).scale(1.0 / (crossK - 1)).subtract(playerVel));
        }
        return new Solution(contAimPoint, contDir, crossK, contAimPoint.distanceTo(launchOrigin), 0.0, Status.MARGINAL, false);
    }

    /**
     * Evaluates the player's actual live crosshair look direction against exact MC 26.2 physics + Vanilla spread.
     * Returns hit confidence in {@code [0, 1]} (0 if center ray misses).
     */
    public static double evaluateAim(
        Vec3 pearlPos,
        Vec3 pearlVel,
        Vec3 launchOrigin,
        Vec3 lookDirection,
        Vec3 playerVel,
        double floorY,
        int pearlTickCount
    ) {
        Vec3[] pos = new Vec3[MAX_TICKS + 1];
        int maxK = simulatePearl(pearlPos, pearlVel, floorY, pos);
        if (maxK == 0) return 0.0;
        Vec3 dir = safeNormalize(lookDirection);
        if (!simulateDiscreteHit(pos, maxK, launchOrigin, dir.scale(WIND_SPEED).add(playerVel), floorY, pearlTickCount).hit) {
            return 0.0;
        }
        return evaluateSpreadConfidence(pos, maxK, launchOrigin, dir, playerVel, floorY, pearlTickCount);
    }

    /**
     * Exact discrete Vanilla tick-by-tick check: on tick {@code k >= 1}, pearl sweeps {@code [pos[k-1], pos[k]]}
     * against the WindCharge's inflated bounding box positioned at {@code launchOrigin + (k - 1) * chargeVel}.
     */
    public static boolean willHitDiscretely(
        Vec3 pearlPos,
        Vec3 pearlVel,
        Vec3 launchOrigin,
        Vec3 aimDirection,
        Vec3 playerVel,
        double floorY
    ) {
        return willHitDiscretely(pearlPos, pearlVel, launchOrigin, aimDirection, playerVel, floorY, 8);
    }

    public static boolean willHitDiscretely(
        Vec3 pearlPos,
        Vec3 pearlVel,
        Vec3 launchOrigin,
        Vec3 aimDirection,
        Vec3 playerVel,
        double floorY,
        int pearlTickCount
    ) {
        Vec3[] pos = new Vec3[MAX_TICKS + 1];
        int maxK = simulatePearl(pearlPos, pearlVel, floorY, pos);
        if (maxK == 0) return false;
        Vec3 chargeVel = safeNormalize(aimDirection).scale(WIND_SPEED).add(playerVel);
        return simulateDiscreteHit(pos, maxK, launchOrigin, chargeVel, floorY, pearlTickCount).hit;
    }

    private static int simulatePearl(Vec3 pearlPos, Vec3 pearlVel, double floorY, Vec3[] pos) {
        pos[0] = pearlPos;
        Vec3 vel = pearlVel;
        int maxK = 0;
        for (int k = 1; k <= MAX_TICKS; k++) {
            vel = vel.add(0.0, -PEARL_GRAVITY, 0.0).scale(PEARL_DRAG);
            pos[k] = pos[k - 1].add(vel);
            maxK = k;
            if (pos[k].y <= floorY + 0.05) {
                break;
            }
        }
        return maxK;
    }

    private static double evaluateSpreadConfidence(
        Vec3[] pos,
        int maxK,
        Vec3 launchOrigin,
        Vec3 unitDir,
        Vec3 playerVel,
        double floorY,
        int pearlTickCount
    ) {
        double weightHit = SPREAD_SAMPLES[0][3]; // center ray already verified hitting
        for (int i = 1; i < SPREAD_SAMPLES.length; i++) {
            double[] s = SPREAD_SAMPLES[i];
            Vec3 perturbedVel = new Vec3(
                (unitDir.x + s[0]) * WIND_SPEED + playerVel.x,
                (unitDir.y + s[1]) * WIND_SPEED + playerVel.y,
                (unitDir.z + s[2]) * WIND_SPEED + playerVel.z
            );
            if (simulateDiscreteHit(pos, maxK, launchOrigin, perturbedVel, floorY, pearlTickCount).hit) {
                weightHit += s[3];
            }
        }
        return weightHit / SPREAD_MAX_WEIGHT;
    }

    private static HitResultCheck simulateDiscreteHit(Vec3[] pos, int maxK, Vec3 launchOrigin, Vec3 chargeVel, double floorY, int pearlTickCount) {
        Vec3 chargePos = launchOrigin;
        for (int k = 1; k <= maxK; k++) {
            Vec3 p0 = pos[k - 1];
            Vec3 p1 = pos[k];
            if (p0.y <= floorY + 0.05) {
                break;
            }
            AABB box = windChargeBox(chargePos, marginAt(pearlTickCount + k));
            if (box.clip(p0, p1).isPresent()) {
                Vec3 center = chargePos.add(0.0, CHARGE_HEIGHT * 0.5, 0.0);
                double err = pointToSegmentDistance(center, p0, p1);
                return new HitResultCheck(true, k, err);
            }
            chargePos = chargePos.add(chargeVel);
            if (chargePos.y <= floorY - 0.5) {
                break;
            }
        }
        return HitResultCheck.MISS;
    }

    private static double marginAt(int tickCount) {
        return Math.max(0.0, Math.min(HIT_MARGIN, (tickCount - 2) / 20.0));
    }

    private static Vec3 targetOriginForTick(Vec3[] pos, int k) {
        return pos[k - 1].lerp(pos[k], 0.5).subtract(0.0, CHARGE_HEIGHT * 0.5, 0.0);
    }

    private static AABB windChargeBox(Vec3 pos, double margin) {
        double hw = CHARGE_HALF_WIDTH + margin;
        return new AABB(
            pos.x - hw,
            pos.y - margin,
            pos.z - hw,
            pos.x + hw,
            pos.y + CHARGE_HEIGHT + margin,
            pos.z + hw
        );
    }

    private static Vec3 perpendicularBasis(Vec3 unit) {
        if (Math.abs(unit.y) < 0.9) {
            return safeNormalize(new Vec3(-unit.z, 0.0, unit.x));
        }
        return new Vec3(1.0, 0.0, 0.0);
    }

    private static double pointToSegmentDistance(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lenSq = ab.lengthSqr();
        if (lenSq < 1.0e-12) return p.distanceTo(a);
        double t = Math.clamp(p.subtract(a).dot(ab) / lenSq, 0.0, 1.0);
        return p.distanceTo(a.add(ab.scale(t)));
    }

    private static Vec3 safeNormalize(Vec3 v) {
        double len = v.length();
        return len > 1.0e-7 ? v.scale(1.0 / len) : new Vec3(0.0, 1.0, 0.0);
    }

    private static double[][] buildSpreadSamples() {
        java.util.List<double[]> list = new java.util.ArrayList<>(37);
        list.add(new double[]{0.0, 0.0, 0.0, 4.0});
        double[][] shells = {{0.0055, 2.5}, {0.0115, 1.0}};
        for (double[] shell : shells) {
            double s = shell[0];
            double w = shell[1];
            for (int ix = -1; ix <= 1; ix++) {
                for (int iy = -1; iy <= 1; iy++) {
                    for (int iz = -1; iz <= 1; iz++) {
                        int nz = Math.abs(ix) + Math.abs(iy) + Math.abs(iz);
                        if (nz == 1 || nz == 2) {
                            list.add(new double[]{ix * s, iy * s, iz * s, w});
                        }
                    }
                }
            }
        }
        return list.toArray(double[][]::new);
    }

    private static double computeMaxWeight() {
        double sum = 0.0;
        for (double[] s : SPREAD_SAMPLES) sum += s[3];
        return sum;
    }

    /**
     * Exact continuous collision detection (CCD) between a moving Ender Pearl segment (pearlStart -> pearlEnd)
     * and a moving/falling target bounding box translating from targetStartPos -> targetEndPos over one tick.
     * Prevents both Vanilla AABB.clip(start-inside) misses and high-speed vertical tunneling.
     */
    public static Optional<Vec3> sweepMovingTarget(
        Vec3 pearlStart,
        Vec3 pearlEnd,
        AABB targetEndBox,
        Vec3 targetStartPos,
        Vec3 targetEndPos,
        double margin
    ) {
        Vec3 deltaP = pearlEnd.subtract(pearlStart);
        Vec3 deltaB = targetEndPos.subtract(targetStartPos);
        AABB boxStart = targetEndBox.move(targetStartPos.subtract(targetEndPos)).inflate(margin);
        AABB boxEnd = targetEndBox.inflate(margin);

        if (boxStart.contains(pearlStart) || boxEnd.contains(pearlStart)) {
            return Optional.of(pearlStart);
        }
        Vec3 relDelta = deltaP.subtract(deltaB);
        Vec3 relEnd = pearlStart.add(relDelta);
        Optional<Vec3> relHit = boxStart.clip(pearlStart, relEnd);
        if (relHit.isPresent()) {
            double relLen = relDelta.length();
            double u = relLen < 1.0E-9 ? 0.0 : Math.clamp(relHit.get().subtract(pearlStart).length() / relLen, 0.0, 1.0);
            return Optional.of(pearlStart.add(deltaP.scale(u)));
        }
        Optional<Vec3> endHit = boxEnd.clip(pearlStart, pearlEnd);
        if (endHit.isPresent()) {
            return endHit;
        }
        if (boxEnd.contains(pearlEnd)) {
            return Optional.of(pearlEnd);
        }
        return Optional.empty();
    }

    private record HitResultCheck(boolean hit, int tick, double centerError) {
        static final HitResultCheck MISS = new HitResultCheck(false, -1, Double.POSITIVE_INFINITY);
    }
}
