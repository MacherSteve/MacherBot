package de.pvptrainer;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

final class ObsidianEnclosure {
    private static final double HALF_WIDTH = .3;
    private static final double CRAWL_HEIGHT = .6;
    private static final double CROUCH_HEIGHT = 1.5;
    private static final double JUMP_RISE = 1.252;
    private static final double EPS = 1e-6;

    static boolean closed(Level level, BlockPos feet) {
        List<AABB> floor = new ArrayList<>();
        addShape(level, feet.below(), floor);
        List<AABB> roof = new ArrayList<>();
        addShape(level, feet.above(2), roof);
        addShape(level, feet.above(3), roof);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            int dx = side.getStepX(), dz = side.getStepZ();
            List<AABB> solids = new ArrayList<>(roof);
            for (int y = -1; y <= 3; y++) addShape(level, feet.relative(side).above(y), solids);
            // Include each interval in which a player-width passage can open beside a partial block.
            List<Double> lanes = new ArrayList<>(List.of(-.2, 0., .2));
            double center = (dx == 0 ? feet.getX() : feet.getZ()) + .5;
            for (AABB box : solids) {
                double left = (dx == 0 ? box.minX : box.minZ) - center - HALF_WIDTH;
                double right = (dx == 0 ? box.maxX : box.maxZ) - center + HALF_WIDTH;
                if (Math.abs(left) < .2) lanes.add(left);
                if (Math.abs(right) < .2) lanes.add(right);
            }
            for (double lane : lanes) {
                double x = feet.getX() + .5 + lane * Math.abs(dz);
                double z = feet.getZ() + .5 + lane * Math.abs(dx);
                AABB crawl = new AABB(x-HALF_WIDTH, feet.getY(), z-HALF_WIDTH,
                        x+HALF_WIDTH, feet.getY()+CRAWL_HEIGHT, z+HALF_WIDTH);
                if (clear(floor, crawl.move(0, -1, 0).expandTowards(0, .4, 0))) return false;
                AABB passage = crawl.expandTowards(dx, 0, dz);
                if (clear(solids, passage)) return false;
                for (AABB obstacle : solids) {
                    double rise = obstacle.maxY - feet.getY();
                    if (rise <= 0 || rise > JUMP_RISE) continue;
                    double headroom = rise <= .6 ? 0 : CROUCH_HEIGHT - CRAWL_HEIGHT;
                    if (clear(solids, crawl.expandTowards(0, rise + headroom, 0))
                            && clear(solids, passage.move(0, rise, 0).expandTowards(0, headroom, 0))) return false;
                }
            }
        }
        return true;
    }

    private static void addShape(Level level, BlockPos pos, List<AABB> boxes) {
        for (AABB local : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs())
            boxes.add(local.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private static boolean clear(List<AABB> boxes, AABB volume) {
        AABB interior = volume.deflate(EPS);
        return boxes.stream().noneMatch(interior::intersects);
    }
}
