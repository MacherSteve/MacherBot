package de.pvptrainer.ui;

import de.pvptrainer.TrainerClient;
import de.pvptrainer.arena.ForgeArena;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.PearlInterceptSolver;
import de.pvptrainer.session.TrainingSession;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * Dynamic real-time horizon & sky lead indicator for {@link Drill#PEARL_CATCH_GUIDE}.
 * Reads the exact unquantized server-side pearl state ({@link TrainingSession.PearlSnapshot}) so there is zero
 * Slow-Mo {@code partialTick} lag or network velocity quantization, computes the centroid-optimized WindCharge
 * intercept via {@link PearlInterceptSolver}, and verifies the player's live crosshair vector directly.
 */
final class PearlCatchHud {
    private static final int GREEN = 0xFF4CD964;
    private static final int SKY = 0xFF5AC8FA;
    private static final int AMBER = 0xFFFFB84D;
    private static final int CORAL = 0xFFFF7A6B;
    private static final int PEARL_RING = 0xCCE0D0FF;
    private static final int PILL_BG = 0xB414161B;
    private static final int SHADOW = 0xAA000000;
    private static final int MARGIN = 22;

    private PearlCatchHud() {}

    static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (de.pvptrainer.compat.McCompat.hudHidden(mc) || mc.player == null || mc.level == null
            || mc.getCameraEntity() != mc.player
            || !TrainingArena.isArena(mc.player.level())) {
            return;
        }
        var status = TrainerClient.status();
        if (!status.active() || status.drill() != Drill.PEARL_CATCH_GUIDE || status.waitingForStart()) {
            return;
        }

        int w = g.guiWidth();
        int h = g.guiHeight();
        int cx = w / 2;
        int cy = h / 2;

        ThrownEnderpearl clientPearl = findOwnedPearl(mc);
        TrainingSession.PearlSnapshot snap = TrainerClient.activePearl();
        // If the client already saw the pearl removed, hide immediately instead of waiting for next slow-mo tick.
        if (snap != null) {
            Entity byId = mc.level.getEntity(snap.entityId());
            if (byId != null && byId.isRemoved()) {
                snap = null;
            }
        }
        if (snap == null && clientPearl == null) {
            String prog = status.progress();
            if (prog != null && prog.contains("Luft-Teleport")) {
                drawPill(g, mc, "Jetzt Mace-Smash!", cx, cy + 16, SKY);
            } else {
                drawPill(g, mc, "Perle werfen (Indikator aktiv)", cx, cy + 16, 0xFFD8DEE9);
            }
            return;
        }

        float pt = delta.getGameTimeDeltaPartialTick(false);
        // Exact un-interpolated server pearl state for ballistics (zero Slow-Mo partialTick lag!)
        Vec3 exactPearlPos = snap != null ? snap.pos() : clientPearl.position();
        Vec3 exactPearlVel = snap != null ? snap.vel() : clientPearl.getDeltaMovement();
        int pearlTick = snap != null ? snap.tickCount() : clientPearl.tickCount;

        // Visual marker smoothly tracks rendered pearl entity
        Vec3 visualPearlPos = clientPearl != null ? clientPearl.getPosition(pt) : exactPearlPos;

        // WindCharge spawns at (player.x, player.eyeY, player.z) and inherits ServerPlayer.getKnownMovement()
        Vec3 launchOrigin = new Vec3(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
        Vec3 knownVel = snap != null ? snap.playerKnownMovement() : mc.player.getDeltaMovement();
        Vec3 inheritedVel = new Vec3(knownVel.x, mc.player.onGround() ? 0.0 : knownVel.y, knownVel.z);
        double floorY = ForgeArena.FLOOR + 1.0;

        PearlInterceptSolver.Solution sol = PearlInterceptSolver.solve(
            exactPearlPos, exactPearlVel, launchOrigin, inheritedVel, floorY, pearlTick
        );

        // Verify the player's ACTUAL live crosshair look vector against exact Vanilla MC 26.2 physics!
        Vec3 liveLook = mc.player.getLookAngle();
        double liveAimConf = sol.discreteHit()
            ? PearlInterceptSolver.evaluateAim(exactPearlPos, exactPearlVel, launchOrigin, liveLook, inheritedVel, floorY, pearlTick)
            : 0.0;
        double angleDeg = Math.toDegrees(Math.acos(Math.clamp(liveLook.normalize().dot(sol.aimDirection()), -1.0, 1.0)));
        boolean lockedOn = sol.discreteHit()
            && liveAimConf >= Math.min(0.75, sol.hitConfidence() * 0.88)
            && liveAimConf >= 0.55
            && angleDeg <= 1.35;

        Camera camera = de.pvptrainer.compat.McCompat.mainCamera(mc);
        Matrix4f viewProj = de.pvptrainer.compat.McCompat.viewRotationProjection(mc, camera, pt);

        // Project required look direction onto sky/horizon (matches player look angle even when moving!)
        Vec3 aimTargetWorld = camera.position().add(sol.aimDirection().scale(Math.max(12.0, sol.distance())));
        Projected aimProj = project(camera, viewProj, aimTargetWorld, w, h);
        Projected pearlProj = project(camera, viewProj, visualPearlPos, w, h);

        boolean onScreen = aimProj.inFront
            && aimProj.x >= MARGIN && aimProj.x <= w - MARGIN
            && aimProj.y >= MARGIN && aimProj.y <= h - MARGIN;

        int pct = (int) Math.round(sol.hitConfidence() * 100.0);
        int livePct = (int) Math.round(liveAimConf * 100.0);

        int color = lockedOn
            ? (sol.status() == PearlInterceptSolver.Status.READY ? GREEN : AMBER)
            : switch (sol.status()) {
                case READY -> SKY;
                case MARGINAL -> AMBER;
                case UNREACHABLE -> CORAL;
            };

        String tag;
        if (lockedOn) {
            tag = "JETZT · " + livePct + " %";
        } else {
            tag = switch (sol.status()) {
                case READY -> "WindCharge · " + sol.interceptTicks() + "t (" + pct + " %)";
                case MARGINAL -> sol.discreteHit() ? "Eng · " + pct + " %" : "Kurz warten";
                case UNREACHABLE -> "Zu tief";
            };
        }

        boolean pearlOnScreen = pearlProj.inFront
            && pearlProj.x >= 8 && pearlProj.x <= w - 8
            && pearlProj.y >= 8 && pearlProj.y <= h - 8;

        if (pearlOnScreen) {
            drawPearlMarker(g, (int) Math.round(pearlProj.x), (int) Math.round(pearlProj.y));
        }

        if (onScreen) {
            int ax = (int) Math.round(aimProj.x);
            int ay = (int) Math.round(aimProj.y);
            if (pearlOnScreen) {
                drawLeadTrail(g, pearlProj.x, pearlProj.y, aimProj.x, aimProj.y, (0x88 << 24) | (color & 0x00FFFFFF));
            }
            drawHorizonReticle(g, ax, ay, color, lockedOn);
            int labelY = ay + 13 <= h - 14 ? ay + 13 : ay - 22;
            drawPill(g, mc, tag, ax, labelY, color);
        } else {
            drawOffscreenIndicator(g, mc, camera, sol.aimDirection(), w, h, color, tag);
        }
    }

    private static ThrownEnderpearl findOwnedPearl(Minecraft mc) {
        ThrownEnderpearl newest = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof ThrownEnderpearl pearl && !pearl.isRemoved() && pearl.getOwner() == mc.player) {
                if (newest == null || pearl.tickCount < newest.tickCount) {
                    newest = pearl;
                }
            }
        }
        return newest;
    }

    private static Projected project(Camera camera, Matrix4f viewProj, Vec3 worldPos, int w, int h) {
        Vec3 cam = camera.position();
        Vector4f clip = new Vector4f(
            (float) (worldPos.x - cam.x),
            (float) (worldPos.y - cam.y),
            (float) (worldPos.z - cam.z),
            1.0F
        );
        viewProj.transform(clip);
        if (clip.w <= 0.001F) {
            return new Projected(w * 0.5, h * 0.5, false);
        }
        double ndcX = clip.x / clip.w;
        double ndcY = clip.y / clip.w;
        double sx = (ndcX * 0.5 + 0.5) * w;
        double sy = (0.5 - ndcY * 0.5) * h;
        return new Projected(sx, sy, true);
    }

    private static void drawPearlMarker(GuiGraphicsExtractor g, int px, int py) {
        g.fill(px - 3, py - 3, px + 4, py - 2, PEARL_RING);
        g.fill(px - 3, py + 3, px + 4, py + 4, PEARL_RING);
        g.fill(px - 3, py - 2, px - 2, py + 3, PEARL_RING);
        g.fill(px + 3, py - 2, px + 4, py + 3, PEARL_RING);
    }

    private static void drawLeadTrail(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int argb) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dist = Math.hypot(dx, dy);
        if (dist < 12.0) return;
        int steps = Math.min(28, Math.max(2, (int) (dist / 6.0)));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int px = (int) Math.round(x0 + dx * t);
            int py = (int) Math.round(y0 + dy * t);
            g.fill(px, py, px + 2, py + 2, argb);
        }
    }

    private static void drawHorizonReticle(GuiGraphicsExtractor g, int x, int y, int color, boolean lockedOn) {
        int r = lockedOn ? 6 : 8;
        int wing = lockedOn ? 10 : 13;
        int gap = r + 3;

        // Subtle dark backing for high contrast against bright sky
        g.fill(x - r - 1, y - r - 1, x + r + 2, y - r + 1, SHADOW);
        g.fill(x - r - 1, y + r, x + r + 2, y + r + 2, SHADOW);
        g.fill(x - r - 1, y - r, x - r + 1, y + r + 1, SHADOW);
        g.fill(x + r, y - r, x + r + 2, y + r + 1, SHADOW);

        // Open corner-bracket ring
        int arm = 4;
        g.fill(x - r, y - r, x - r + arm, y - r + 1, color);
        g.fill(x - r, y - r, x - r + 1, y - r + arm, color);
        g.fill(x + r - arm + 1, y - r, x + r + 1, y - r + 1, color);
        g.fill(x + r, y - r, x + r + 1, y - r + arm, color);
        g.fill(x - r, y + r, x - r + arm, y + r + 1, color);
        g.fill(x - r, y + r - arm + 1, x - r + 1, y + r + 1, color);
        g.fill(x + r - arm + 1, y + r, x + r + 1, y + r + 1, color);
        g.fill(x + r, y + r - arm + 1, x + r + 1, y + r + 1, color);

        // Horizontal horizon wings (left & right of the reticle)
        int wingColor = (0xCC << 24) | (color & 0x00FFFFFF);
        g.fill(x - gap - wing, y, x - gap, y + 1, wingColor);
        g.fill(x + gap + 1, y, x + gap + wing + 1, y + 1, wingColor);
        // Vertical fine alignment ticks
        g.fill(x, y - r - 3, x + 1, y - r, wingColor);
        g.fill(x, y + r + 1, x + 1, y + r + 4, wingColor);

        // Exact 1x1 center pixel with 3x3 dark outline for pinpoint crosshair alignment
        g.fill(x - 1, y - 1, x + 2, y + 2, SHADOW);
        g.fill(x, y, x + 1, y + 1, color);
    }

    private static void drawOffscreenIndicator(
        GuiGraphicsExtractor g,
        Minecraft mc,
        Camera camera,
        Vec3 aimDir,
        int w,
        int h,
        int color,
        String tag
    ) {
        Vector3fc left = camera.leftVector();
        Vector3fc up = camera.upVector();
        double rightDot = -(aimDir.x * left.x() + aimDir.y * left.y() + aimDir.z * left.z());
        double upDot = aimDir.x * up.x() + aimDir.y * up.y() + aimDir.z * up.z();
        double len = Math.hypot(rightDot, upDot);
        double dirX = len > 1.0e-5 ? rightDot / len : 0.0;
        double dirY = len > 1.0e-5 ? -upDot / len : -1.0;

        double cx = w * 0.5;
        double cy = h * 0.5;
        double maxX = cx - MARGIN;
        double maxY = cy - MARGIN;
        double scaleX = Math.abs(dirX) > 1.0e-5 ? maxX / Math.abs(dirX) : Double.POSITIVE_INFINITY;
        double scaleY = Math.abs(dirY) > 1.0e-5 ? maxY / Math.abs(dirY) : Double.POSITIVE_INFINITY;
        double scale = Math.min(scaleX, scaleY);
        int ex = (int) Math.round(Mth.clamp(cx + dirX * scale, MARGIN, w - MARGIN));
        int ey = (int) Math.round(Mth.clamp(cy + dirY * scale, MARGIN, h - MARGIN));

        int tipX = (int) Math.round(ex + dirX * 7.0);
        int tipY = (int) Math.round(ey + dirY * 7.0);
        double perpX = -dirY;
        double perpY = dirX;
        int back1X = (int) Math.round(ex - dirX * 4.0 + perpX * 5.0);
        int back1Y = (int) Math.round(ey - dirY * 4.0 + perpY * 5.0);
        int back2X = (int) Math.round(ex - dirX * 4.0 - perpX * 5.0);
        int back2Y = (int) Math.round(ey - dirY * 4.0 - perpY * 5.0);

        drawDotLine(g, back1X, back1Y, tipX, tipY, color);
        drawDotLine(g, back2X, back2Y, tipX, tipY, color);
        g.fill(tipX - 1, tipY - 1, tipX + 2, tipY + 2, color);

        int pillX = Mth.clamp(ex, 54, w - 54);
        int pillY = ey < h / 2 ? ey + 11 : ey - 19;
        drawPill(g, mc, tag, pillX, pillY, color);
    }

    private static void drawDotLine(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(x1 - x0, y1 - y0)));
        for (int i = 0; i <= steps; i++) {
            int x = (int) Math.round(x0 + (x1 - x0) * (double) i / steps);
            int y = (int) Math.round(y0 + (y1 - y0) * (double) i / steps);
            g.fill(x, y, x + 2, y + 2, color);
        }
    }

    private static void drawPill(GuiGraphicsExtractor g, Minecraft mc, String text, int centerX, int topY, int textColor) {
        int tw = mc.font.width(text);
        int px = Math.clamp(centerX - tw / 2 - 5, 4, Math.max(4, g.guiWidth() - tw - 14));
        int py = Math.clamp(topY, 4, Math.max(4, g.guiHeight() - 16));
        g.fill(px, py, px + tw + 10, py + 12, PILL_BG);
        g.fill(px, py, px + tw + 10, py + 1, (0x55 << 24) | (textColor & 0x00FFFFFF));
        g.text(mc.font, text, px + 5, py + 2, textColor, false);
    }

    private record Projected(double x, double y, boolean inFront) {}
}
