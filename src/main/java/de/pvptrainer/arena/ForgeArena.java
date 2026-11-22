package de.pvptrainer.arena;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class ForgeArena {
    public static final int FLOOR = 64;
    static final int RADIUS = 28;
    static final int MIN = 60;
    public static final int TOP = 182;
    private static final int SIZE = 57;

    static final int LARGE_RADIUS = 56;
    static final int LARGE_DEPTH = 80;
    // FLOOR (64) down through -16 = 80 solid diggable blocks below floor; bedrock at -17.
    static final int LARGE_MIN = FLOOR - LARGE_DEPTH - 1;
    private static final int LARGE_SIZE = LARGE_RADIUS * 2 + 1;
    private static final int LARGE_WALL_TOP = FLOOR + 22;
    private static final int STANDARD_PLAY_RADIUS = 19;
    private static final int LARGE_PLAY_RADIUS = 45;

    private static final String[] EMBLEM = new String[]{
        "##.....##",
        "###...###",
        "####.####",
        "##.###.##",
        "##..#..##",
        "##.....##",
        "##.....##"
    };

    private static final byte[] LARGE_HILLS = computeLargeHills();
    private static volatile boolean activeLarge;

    private final BlockState[] blocks = new BlockState[399627];

    private static final class Holder {
        static final BlockState AIR = Blocks.AIR.defaultBlockState();
        static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
        static final BlockState BLACKSTONE = Blocks.BLACKSTONE.defaultBlockState();
        static final BlockState POLISHED_BLACKSTONE = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
        static final BlockState POLISHED_BLACKSTONE_BRICKS = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        static final BlockState CRACKED_BLACKSTONE = Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        static final BlockState CHISELED_BLACKSTONE = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
        static final BlockState BLACKSTONE_WALL = Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState();
        static final BlockState SMOOTH_BASALT = Blocks.SMOOTH_BASALT.defaultBlockState();
        static final BlockState POLISHED_BASALT = Blocks.POLISHED_BASALT.defaultBlockState();
        static final BlockState RED_NETHER_BRICKS = Blocks.RED_NETHER_BRICKS.defaultBlockState();
        static final BlockState NETHER_BRICKS = Blocks.NETHER_BRICKS.defaultBlockState();
        static final BlockState CHISELED_NETHER_BRICKS = Blocks.CHISELED_NETHER_BRICKS.defaultBlockState();
        static final BlockState SHROOMLIGHT = Blocks.SHROOMLIGHT.defaultBlockState();
        static final BlockState MAGMA_BLOCK = Blocks.MAGMA_BLOCK.defaultBlockState();
        static final BlockState IRON_CHAIN = Blocks.IRON_CHAIN.defaultBlockState();
        static final BlockState RED_GLASS = ((Block) Blocks.STAINED_GLASS.red()).defaultBlockState();
        static final BlockState HANGING_LANTERN = Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true);
        static final ForgeArena STANDARD = new ForgeArena();
        static final Map<Long, BlockState> LARGE_DETAILS = buildLargeDetails();
    }

    public static boolean isLargeActive() {
        return activeLarge;
    }

    public static int minFloor() {
        return minFloor(activeLarge);
    }

    public static int minFloor(boolean large) {
        return large ? LARGE_MIN : MIN;
    }

    public static int playRadius() {
        return activeLarge ? LARGE_PLAY_RADIUS : STANDARD_PLAY_RADIUS;
    }

    public static int largeDepthBlocks() {
        return LARGE_DEPTH;
    }

    public static int largeRadius() {
        return LARGE_RADIUS;
    }

    public static int largeHillOffset(int x, int z) {
        if (Math.abs(x) > LARGE_RADIUS || Math.abs(z) > LARGE_RADIUS) return 0;
        return LARGE_HILLS[(x + LARGE_RADIUS) * LARGE_SIZE + (z + LARGE_RADIUS)];
    }

    private static int largeEdge(int x, int z) {
        int maxAxis = Math.max(Math.abs(x), Math.abs(z));
        int manhattan = Math.abs(x) + Math.abs(z);
        return Math.max(maxAxis - 50, manhattan - 75);
    }

    private static byte[] computeLargeHills() {
        byte[] map = new byte[LARGE_SIZE * LARGE_SIZE];
        for (int x = -LARGE_RADIUS; x <= LARGE_RADIUS; x++) {
            for (int z = -LARGE_RADIUS; z <= LARGE_RADIUS; z++) {
                int edge = largeEdge(x, z);
                if (edge >= -2) continue;
                double dist = Math.hypot(x, z);
                if (dist <= 10.5) continue;
                double innerT = Math.min(1.0, (dist - 10.5) / 9.0);
                double innerBlend = innerT * innerT * (3.0 - 2.0 * innerT);
                double outerT = Math.min(1.0, (-2 - edge) / 6.0);
                double outerBlend = outerT * outerT * (3.0 - 2.0 * outerT);
                double wave = 0.50
                    + 0.25 * Math.sin(x * 0.105 + 0.5) * Math.cos(z * 0.095 - 0.3)
                    + 0.16 * Math.sin((x + z) * 0.075)
                    + 0.10 * Math.cos((x - z) * 0.12);
                int h = (int) Math.round(innerBlend * outerBlend * Math.max(0.0, wave) * 4.2);
                map[(x + LARGE_RADIUS) * LARGE_SIZE + (z + LARGE_RADIUS)] = (byte) Math.clamp(h, 0, 4);
            }
        }
        return map;
    }

    private ForgeArena() {
        Arrays.fill(this.blocks, Holder.AIR);

        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                this.buildColumn(x, z);
            }
        }

        for (int side = 0; side < 4; side++) {
            this.buildGate(side);
            for (int mirror : new int[]{-1, 1}) {
                this.buildButtress(side, mirror);
                this.buildFurnace(side, mirror);
            }
        }
    }

    public static void restore(ServerLevel level) {
        restore(level, activeLarge);
    }

    public static void restore(ServerLevel level, boolean large) {
        if (large) {
            activeLarge = true;
            restoreLarge(level);
            return;
        }
        boolean hadLarge = activeLarge
            || !level.getBlockState(new BlockPos(35, FLOOR, 0)).isAir()
            || !level.getBlockState(new BlockPos(0, LARGE_MIN, 0)).isAir();
        activeLarge = false;
        if (hadLarge) {
            clearLargeResiduals(level);
        }
        restoreStandard(level);
    }

    private static void restoreStandard(ServerLevel level) {
        BoundingBox box = new BoundingBox(-RADIUS, MIN, -RADIUS, RADIUS, TOP, RADIUS);
        level.getBlockTicks().clearArea(box);
        level.getFluidTicks().clearArea(box);
        MutableBlockPos cursor = new MutableBlockPos();
        int idx = 0;

        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                for (int y = MIN; y <= TOP; y++) {
                    cursor.set(x, y, z);
                    BlockState expected = Holder.STANDARD.blocks[idx++];
                    if (level.getBlockState(cursor) != expected) {
                        level.setBlock(cursor, expected, 818);
                    }
                }
            }
        }
    }

    private static void clearLargeResiduals(ServerLevel level) {
        BoundingBox box = new BoundingBox(-LARGE_RADIUS, LARGE_MIN, -LARGE_RADIUS, LARGE_RADIUS, TOP, LARGE_RADIUS);
        level.getBlockTicks().clearArea(box);
        level.getFluidTicks().clearArea(box);
        MutableBlockPos cursor = new MutableBlockPos();
        int minChunk = Math.floorDiv(-LARGE_RADIUS, 16);
        int maxChunk = Math.floorDiv(LARGE_RADIUS, 16);

        for (int cx = minChunk; cx <= maxChunk; cx++) {
            int xStart = Math.max(-LARGE_RADIUS, cx << 4);
            int xEnd = Math.min(LARGE_RADIUS, (cx << 4) + 15);
            for (int cz = minChunk; cz <= maxChunk; cz++) {
                int zStart = Math.max(-LARGE_RADIUS, cz << 4);
                int zEnd = Math.min(LARGE_RADIUS, (cz << 4) + 15);
                LevelChunk chunk = level.getChunk(cx, cz);
                int minSec = Math.max(0, (LARGE_MIN - chunk.getMinY()) >> 4);
                int maxSec = Math.min(chunk.getSections().length - 1, (TOP - chunk.getMinY()) >> 4);

                for (int sec = minSec; sec <= maxSec; sec++) {
                    LevelChunkSection section = chunk.getSection(sec);
                    if (section.hasOnlyAir()) continue;
                    int baseY = chunk.getMinY() + (sec << 4);
                    int yStart = Math.max(LARGE_MIN, baseY);
                    int yEnd = Math.min(TOP, baseY + 15);
                    for (int x = xStart; x <= xEnd; x++) {
                        boolean inStdX = x >= -RADIUS && x <= RADIUS;
                        int lx = x & 15;
                        for (int z = zStart; z <= zEnd; z++) {
                            boolean inStdXZ = inStdX && z >= -RADIUS && z <= RADIUS;
                            int lz = z & 15;
                            for (int y = yStart; y <= yEnd; y++) {
                                if (inStdXZ && y >= MIN) continue;
                                if (section.getBlockState(lx, y & 15, lz) != Holder.AIR) {
                                    level.setBlock(cursor.set(x, y, z), Holder.AIR, 818);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void restoreLarge(ServerLevel level) {
        BoundingBox box = new BoundingBox(-LARGE_RADIUS, LARGE_MIN, -LARGE_RADIUS, LARGE_RADIUS, TOP, LARGE_RADIUS);
        level.getBlockTicks().clearArea(box);
        level.getFluidTicks().clearArea(box);
        MutableBlockPos cursor = new MutableBlockPos();
        int minChunk = Math.floorDiv(-LARGE_RADIUS, 16);
        int maxChunk = Math.floorDiv(LARGE_RADIUS, 16);

        for (int cx = minChunk; cx <= maxChunk; cx++) {
            int xStart = Math.max(-LARGE_RADIUS, cx << 4);
            int xEnd = Math.min(LARGE_RADIUS, (cx << 4) + 15);
            for (int cz = minChunk; cz <= maxChunk; cz++) {
                int zStart = Math.max(-LARGE_RADIUS, cz << 4);
                int zEnd = Math.min(LARGE_RADIUS, (cz << 4) + 15);
                LevelChunk chunk = level.getChunk(cx, cz);
                int minSec = Math.max(0, (LARGE_MIN - chunk.getMinY()) >> 4);
                int maxSec = Math.min(chunk.getSections().length - 1, (TOP - chunk.getMinY()) >> 4);

                for (int sec = minSec; sec <= maxSec; sec++) {
                    LevelChunkSection section = chunk.getSection(sec);
                    int baseY = chunk.getMinY() + (sec << 4);
                    int yStart = Math.max(LARGE_MIN, baseY);
                    int yEnd = Math.min(TOP, baseY + 15);
                    // Above the highest gate/buttress every expected block is air.
                    if (yStart > LARGE_WALL_TOP && section.hasOnlyAir()) continue;

                    for (int x = xStart; x <= xEnd; x++) {
                        int lx = x & 15;
                        for (int z = zStart; z <= zEnd; z++) {
                            int lz = z & 15;
                            int edge = largeEdge(x, z);
                            if (edge > 5) {
                                if (!section.hasOnlyAir()) {
                                    for (int y = yStart; y <= yEnd; y++) {
                                        if (section.getBlockState(lx, y & 15, lz) != Holder.AIR) {
                                            level.setBlock(cursor.set(x, y, z), Holder.AIR, 818);
                                        }
                                    }
                                }
                                continue;
                            }
                            int surfaceY = FLOOR + largeHillOffset(x, z);
                            int maxAxis = Math.max(Math.abs(x), Math.abs(z));
                            int minAxis = Math.min(Math.abs(x), Math.abs(z));
                            int manhattan = Math.abs(x) + Math.abs(z);
                            for (int y = yStart; y <= yEnd; y++) {
                                BlockState expected = largeBlockAt(x, y, z, edge, surfaceY, maxAxis, minAxis, manhattan);
                                if (section.getBlockState(lx, y & 15, lz) != expected) {
                                    level.setBlock(cursor.set(x, y, z), expected, 818);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static BlockState largeBlockAt(int x, int y, int z, int edge, int surfaceY, int maxAxis, int minAxis, int manhattan) {
        if (y > LARGE_WALL_TOP) return Holder.AIR;

        // Detail overrides (4 grand Forge gates, M-emblem, buttresses, furnaces, hanging lanterns)
        if (y > FLOOR && edge >= -5) {
            BlockState detail = Holder.LARGE_DETAILS.get(BlockPos.asLong(x, y, z));
            if (detail != null) return detail;
        }

        if (y == LARGE_MIN) return Holder.BEDROCK;
        if (y < surfaceY) {
            if (y < FLOOR - 4) {
                int depth = FLOOR - y;
                if (depth % 12 == 0) return Holder.SMOOTH_BASALT;
                if (depth % 12 == 6 && Math.floorMod(x * 7 + z * 13 + y * 5, 11) == 0) {
                    return Holder.POLISHED_BLACKSTONE;
                }
            }
            return Holder.BLACKSTONE;
        }
        if (y == surfaceY) {
            return largeFloorTile(x, z, maxAxis, minAxis, manhattan, edge, surfaceY - FLOOR);
        }

        int relY = y - FLOOR;
        if (edge == -2 && relY == 1) return Holder.POLISHED_BLACKSTONE;
        if (edge == -1) {
            if (relY == 1) return Holder.POLISHED_BLACKSTONE_BRICKS;
            if (relY == 2) return Holder.RED_NETHER_BRICKS;
        }
        if (edge >= 0 && edge <= 3) {
            boolean diag = manhattan - 75 >= maxAxis - 50;
            int wallHeight = diag ? 10 : 9;
            if (diag && Math.abs(Math.abs(x) - Math.abs(z)) <= 4) {
                wallHeight = 13;
            }
            if (relY >= 1 && relY <= wallHeight) {
                if (relY == 2 || relY == wallHeight - 1) return Holder.RED_NETHER_BRICKS;
                if (relY == 3 || relY == wallHeight) return Holder.POLISHED_BLACKSTONE;
                if (relY >= 4 && relY < wallHeight - 1 && Math.floorMod(x * 13 + z * 17 + relY * 11, 19) == 0) {
                    return Holder.CRACKED_BLACKSTONE;
                }
                return Holder.POLISHED_BLACKSTONE_BRICKS;
            }
            if (minAxis % 4 < 2 && edge <= 1) {
                if (relY == wallHeight + 1) return Holder.BLACKSTONE;
                if (diag && relY == wallHeight + 2) return Holder.BLACKSTONE_WALL;
            }
        }
        return Holder.AIR;
    }

    private static BlockState largeFloorTile(int x, int z, int maxAxis, int minAxis, int manhattan, int edge, int hill) {
        if (edge >= -3) return Holder.POLISHED_BLACKSTONE_BRICKS;
        int outerRing = Math.max(maxAxis - 36, manhattan - 54);
        int midRing = Math.max(maxAxis - 24, manhattan - 36);
        int innerRing = Math.max(maxAxis - 10, manhattan - 15);
        if (outerRing == 0) return Holder.RED_NETHER_BRICKS;
        if (midRing == 0 || innerRing == 0) return Holder.POLISHED_BLACKSTONE;
        if (minAxis == 0 && maxAxis >= 4 && maxAxis % 4 == 0) return Holder.CHISELED_BLACKSTONE;
        if (minAxis <= 1 && maxAxis >= 33 && maxAxis <= 35 && maxAxis + minAxis == 35) return Holder.RED_NETHER_BRICKS;
        if (maxAxis == 1) return Holder.POLISHED_BLACKSTONE;
        if (hill >= 3 && Math.floorMod(x + z, 5) == 0) return Holder.POLISHED_BLACKSTONE;
        return Math.floorMod(x, 6) != 3 && Math.floorMod(z, 6) != 3 ? Holder.SMOOTH_BASALT : Holder.POLISHED_BLACKSTONE_BRICKS;
    }

    private static Map<Long, BlockState> buildLargeDetails() {
        Map<Long, BlockState> details = new HashMap<>(4096);
        for (int side = 0; side < 4; side++) {
            buildLargeGate(details, side);
            for (int mirror : new int[]{-1, 1}) {
                buildLargeButtress(details, side, mirror, 7, 9);
                buildLargeFurnace(details, side, mirror, 10, 14);
                buildLargeButtress(details, side, mirror, 18, 20);
                buildLargeFurnace(details, side, mirror, 21, 24);
            }
        }
        return details;
    }

    private static void putLargeFacing(Map<Long, BlockState> map, int side, int lateral, int depth, int relY, BlockState state) {
        int x, z;
        switch (side) {
            case 0 -> { x = lateral; z = depth; }
            case 1 -> { x = depth; z = -lateral; }
            case 2 -> { x = -lateral; z = -depth; }
            case 3 -> { x = -depth; z = lateral; }
            default -> throw new IllegalArgumentException("Ungültige Torseite");
        }
        map.put(BlockPos.asLong(x, FLOOR + relY, z), state);
    }

    private static void buildLargeGate(Map<Long, BlockState> map, int side) {
        for (int lat = -6; lat <= 6; lat++) {
            int abs = Math.abs(lat);
            int arch = 14 - Math.max(0, abs - 3);
            for (int y = 2; y <= arch; y++) {
                for (int d = 48; d <= 51; d++) {
                    putLargeFacing(map, side, lat, d, y, Holder.AIR);
                }
                putLargeFacing(map, side, lat, 52, y, Holder.NETHER_BRICKS);
                if (side != 0 && abs <= 5 && y >= 3 && y <= 7) {
                    putLargeFacing(map, side, lat, 52, y, y == 3 ? Holder.SHROOMLIGHT : Holder.MAGMA_BLOCK);
                    if (abs % 2 == 0) {
                        putLargeFacing(map, side, lat, 51, y, Holder.POLISHED_BASALT);
                    }
                }
            }
            putLargeFacing(map, side, lat, 49, arch + 1, Holder.RED_NETHER_BRICKS);
            putLargeFacing(map, side, lat, 48, arch + 2, Holder.POLISHED_BLACKSTONE);
            putLargeFacing(map, side, lat, 49, arch + 2, Holder.POLISHED_BLACKSTONE_BRICKS);
            putLargeFacing(map, side, lat, 50, arch + 2, Holder.POLISHED_BLACKSTONE_BRICKS);
            for (int y = arch + 1; y <= arch + 3; y++) {
                putLargeFacing(map, side, lat, 51, y, Holder.BLACKSTONE);
            }
            putLargeFacing(map, side, lat, 51, arch + 4, Holder.POLISHED_BLACKSTONE_BRICKS);
            if (abs == 5) {
                for (int y = arch - 2; y < arch + 2; y++) {
                    putLargeFacing(map, side, lat, 48, y, Holder.IRON_CHAIN);
                }
                putLargeFacing(map, side, lat, 48, arch - 3, Holder.HANGING_LANTERN);
            }
            if (side == 0 && abs <= 4) {
                for (int row = 0; row < EMBLEM.length; row++) {
                    if (EMBLEM[row].charAt(lat + 4) == '#') {
                        putLargeFacing(map, side, lat, 52, 12 - row, Holder.SHROOMLIGHT);
                        putLargeFacing(map, side, lat, 51, 12 - row, Holder.RED_GLASS);
                    }
                }
            }
        }
        for (int lat = -6; lat <= 6; lat++) {
            putLargeFacing(map, side, lat, 51, 1, Holder.CHISELED_NETHER_BRICKS);
        }
    }

    private static void buildLargeButtress(Map<Long, BlockState> map, int side, int mirror, int from, int to) {
        int mid = (from + to) / 2;
        for (int col = from; col <= to; col++) {
            int maxH = col == mid ? 20 : 17;
            for (int y = 1; y <= maxH; y++) {
                int front = y <= 3 ? 46 : (y <= 8 ? 47 : (y <= 14 ? 48 : 49));
                for (int d = front; d <= 53; d++) {
                    BlockState block = col == mid ? Holder.POLISHED_BASALT : Holder.POLISHED_BLACKSTONE_BRICKS;
                    if (y == 3 || y == 8 || y == 14 || y == 17) block = Holder.CHISELED_BLACKSTONE;
                    putLargeFacing(map, side, mirror * col, d, y, block);
                }
                if (col == mid && y >= 4 && y <= 14 && y != 8) {
                    putLargeFacing(map, side, mirror * col, front + 1, y, Holder.SHROOMLIGHT);
                    putLargeFacing(map, side, mirror * col, front, y, Holder.RED_GLASS);
                }
            }
        }
        putLargeFacing(map, side, mirror * mid, 51, 21, Holder.BLACKSTONE_WALL);
    }

    private static void buildLargeFurnace(Map<Long, BlockState> map, int side, int mirror, int from, int to) {
        int mid = (from + to) / 2;
        for (int col = from; col <= to; col++) {
            for (int y = 2; y <= 7; y++) {
                putLargeFacing(map, side, mirror * col, 49, y, Holder.POLISHED_BLACKSTONE_BRICKS);
                if (y == 3 || y == 4 || y == 5) {
                    putLargeFacing(map, side, mirror * col, 49, y, Holder.SHROOMLIGHT);
                    putLargeFacing(map, side, mirror * col, 48, y, col == mid ? Holder.IRON_CHAIN : Holder.RED_GLASS);
                }
            }
            putLargeFacing(map, side, mirror * col, 48, 6, Holder.CHISELED_NETHER_BRICKS);
        }
    }

    // --- Original Standard Arena (unchanged) ---

    private void buildColumn(int var1, int var2) {
        int var3 = Math.max(Math.abs(var1), Math.abs(var2));
        int var4 = Math.min(Math.abs(var1), Math.abs(var2));
        int var5 = Math.abs(var1) + Math.abs(var2);
        int var6 = Math.max(var3 - 22, var5 - 33);
        if (var6 <= 5) {
            this.put(var1, -4, var2, Blocks.BEDROCK);

            for (int var7 = -3; var7 < 0; var7++) {
                this.put(var1, var7, var2, Blocks.BLACKSTONE);
            }

            this.put(var1, 0, var2, floorTile(var1, var2, var3, var4, var5, var6));
            if (var6 <= 4) {
                this.put(var1, 118, var2, Blocks.AIR);
                if (var6 == 4) {
                    for (int var11 = 1; var11 < 118; var11++) {
                        this.put(var1, var11, var2, Blocks.AIR);
                    }
                }
            }

            if (var6 == -2) {
                this.put(var1, 1, var2, Blocks.POLISHED_BLACKSTONE);
            }

            if (var6 == -1) {
                this.put(var1, 1, var2, Blocks.POLISHED_BLACKSTONE_BRICKS);
                this.put(var1, 2, var2, Blocks.RED_NETHER_BRICKS);
            }

            if (var6 >= 0 && var6 <= 3) {
                boolean var12 = var5 - 33 >= var3 - 22;
                int var8 = var12 ? 8 : 7;
                if (var12 && Math.abs(Math.abs(var1) - Math.abs(var2)) <= 3) {
                    var8 = 11;
                }

                for (int var9 = 1; var9 <= var8; var9++) {
                    Block var10 = Blocks.POLISHED_BLACKSTONE_BRICKS;
                    if (var9 == 2 || var9 == var8 - 1) {
                        var10 = Blocks.RED_NETHER_BRICKS;
                    }

                    if (var9 == 3 || var9 == var8) {
                        var10 = Blocks.POLISHED_BLACKSTONE;
                    }

                    if (var9 >= 4 && var9 < var8 - 1 && Math.floorMod(var1 * 13 + var2 * 17 + var9 * 11, 19) == 0) {
                        var10 = Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS;
                    }

                    this.put(var1, var9, var2, var10);
                }

                if (var4 % 4 < 2 && var6 <= 1) {
                    this.put(var1, var8 + 1, var2, Blocks.BLACKSTONE);
                    if (var12) {
                        this.put(var1, var8 + 2, var2, Blocks.POLISHED_BLACKSTONE_WALL);
                    }
                }
            }
        }
    }

    private static Block floorTile(int var0, int var1, int var2, int var3, int var4, int var5) {
        if (var5 >= -3) {
            return Blocks.POLISHED_BLACKSTONE_BRICKS;
        } else {
            int var6 = Math.max(var2 - 16, var4 - 24);
            int var7 = Math.max(var2 - 10, var4 - 15);
            if (var6 == 0) {
                return Blocks.RED_NETHER_BRICKS;
            } else if (var7 == 0) {
                return Blocks.POLISHED_BLACKSTONE;
            } else if (var3 == 0 && var2 >= 4 && var2 % 4 == 0) {
                return Blocks.CHISELED_POLISHED_BLACKSTONE;
            } else if (var3 <= 1 && var2 >= 13 && var2 <= 15 && var2 + var3 == 15) {
                return Blocks.RED_NETHER_BRICKS;
            } else if (var2 == 1) {
                return Blocks.POLISHED_BLACKSTONE;
            } else {
                return Math.floorMod(var0, 6) != 3 && Math.floorMod(var1, 6) != 3 ? Blocks.SMOOTH_BASALT : Blocks.POLISHED_BLACKSTONE_BRICKS;
            }
        }
    }

    private void buildGate(int var1) {
        for (int var2 = -6; var2 <= 6; var2++) {
            int var3 = Math.abs(var2);
            int var4 = 12 - Math.max(0, var3 - 3);

            for (int var5 = 2; var5 <= var4; var5++) {
                for (int var6 = 20; var6 <= 23; var6++) {
                    this.facing(var1, var2, var6, var5, Blocks.AIR);
                }

                this.facing(var1, var2, 24, var5, Blocks.NETHER_BRICKS);
                if (var1 != 0 && var3 <= 5 && var5 >= 3 && var5 <= 6) {
                    this.facing(var1, var2, 24, var5, var5 == 3 ? Blocks.SHROOMLIGHT : Blocks.MAGMA_BLOCK);
                    if (var3 % 2 == 0) {
                        this.facing(var1, var2, 23, var5, Blocks.POLISHED_BASALT);
                    }
                }
            }

            this.facing(var1, var2, 21, var4 + 1, Blocks.RED_NETHER_BRICKS);
            this.facing(var1, var2, 20, var4 + 2, Blocks.POLISHED_BLACKSTONE);
            this.facing(var1, var2, 21, var4 + 2, Blocks.POLISHED_BLACKSTONE_BRICKS);
            this.facing(var1, var2, 22, var4 + 2, Blocks.POLISHED_BLACKSTONE_BRICKS);

            for (int var8 = var4 + 1; var8 <= var4 + 3; var8++) {
                this.facing(var1, var2, 23, var8, Blocks.BLACKSTONE);
            }

            this.facing(var1, var2, 23, var4 + 4, Blocks.POLISHED_BLACKSTONE_BRICKS);
            if (var3 == 5) {
                for (int var9 = var4 - 2; var9 < var4 + 2; var9++) {
                    this.facing(var1, var2, 20, var9, Blocks.IRON_CHAIN);
                }

                this.facing(var1, var2, 20, var4 - 3, Holder.HANGING_LANTERN);
            }

            if (var1 == 0 && var3 <= 4) {
                for (int var10 = 0; var10 < EMBLEM.length; var10++) {
                    if (EMBLEM[var10].charAt(var2 + 4) == '#') {
                        this.facing(var1, var2, 24, 10 - var10, Blocks.SHROOMLIGHT);
                        this.facing(var1, var2, 23, 10 - var10, (Block) Blocks.STAINED_GLASS.red());
                    }
                }
            }
        }

        for (int var7 = -6; var7 <= 6; var7++) {
            this.facing(var1, var7, 23, 1, Blocks.CHISELED_NETHER_BRICKS);
        }
    }

    private void buildButtress(int var1, int var2) {
        for (int var3 = 7; var3 <= 9; var3++) {
            int var4 = var3 == 8 ? 18 : 15;

            for (int var5 = 1; var5 <= var4; var5++) {
                int var6 = var5 <= 3 ? 18 : (var5 <= 7 ? 19 : (var5 <= 12 ? 20 : 21));

                for (int var7 = var6; var7 <= 25; var7++) {
                    Block var8 = var3 == 8 ? Blocks.POLISHED_BASALT : Blocks.POLISHED_BLACKSTONE_BRICKS;
                    if (var5 == 3 || var5 == 8 || var5 == 13 || var5 == 15) {
                        var8 = Blocks.CHISELED_POLISHED_BLACKSTONE;
                    }

                    this.facing(var1, var2 * var3, var7, var5, var8);
                }

                if (var3 == 8 && var5 >= 4 && var5 <= 12 && var5 != 8) {
                    this.facing(var1, var2 * var3, var6 + 1, var5, Blocks.SHROOMLIGHT);
                    this.facing(var1, var2 * var3, var6, var5, (Block) Blocks.STAINED_GLASS.red());
                }
            }
        }

        this.facing(var1, var2 * 8, 23, 19, Blocks.POLISHED_BLACKSTONE_WALL);
    }

    private void buildFurnace(int var1, int var2) {
        for (int var3 = 10; var3 <= 12; var3++) {
            for (int var4 = 2; var4 <= 6; var4++) {
                this.facing(var1, var2 * var3, 21, var4, Blocks.POLISHED_BLACKSTONE_BRICKS);
                if (var4 == 3 || var4 == 4) {
                    this.facing(var1, var2 * var3, 21, var4, Blocks.SHROOMLIGHT);
                    this.facing(var1, var2 * var3, 20, var4, var3 == 11 ? Blocks.IRON_CHAIN : (Block) Blocks.STAINED_GLASS.red());
                }
            }

            this.facing(var1, var2 * var3, 20, 5, Blocks.CHISELED_NETHER_BRICKS);
        }
    }

    private void facing(int var1, int var2, int var3, int var4, Block var5) {
        this.facing(var1, var2, var3, var4, var5.defaultBlockState());
    }

    private void facing(int var1, int var2, int var3, int var4, BlockState var5) {
        switch (var1) {
            case 0 -> this.put(var2, var4, var3, var5);
            case 1 -> this.put(var3, var4, -var2, var5);
            case 2 -> this.put(-var2, var4, -var3, var5);
            case 3 -> this.put(-var3, var4, var2, var5);
            default -> throw new IllegalArgumentException("Ungültige Torseite");
        }
    }

    private void put(int var1, int var2, int var3, Block var4) {
        this.put(var1, var2, var3, var4.defaultBlockState());
    }

    private void put(int var1, int var2, int var3, BlockState var4) {
        int var5 = ((var1 + RADIUS) * SIZE + var3 + RADIUS) * 123 + FLOOR + var2 - MIN;
        this.blocks[var5] = var4;
    }
}
