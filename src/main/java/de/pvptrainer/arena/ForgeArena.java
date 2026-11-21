package de.pvptrainer.arena;

import java.util.Arrays;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class ForgeArena {
   public static final int FLOOR = 64;
   static final int RADIUS = 28;
   static final int MIN = 60;
   public static final int TOP = 182;
   private static final int SIZE = 57;
   private static final String[] EMBLEM = new String[]{"##.....##", "###...###", "####.####", "##.###.##", "##..#..##", "##.....##", "##.....##"};
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();
   private static final BlockState HANGING_LANTERN = (BlockState)Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true);
   private static final ForgeArena STRUCTURE = new ForgeArena();
   private final BlockState[] blocks = new BlockState[399627];

   private ForgeArena() {
      Arrays.fill(this.blocks, AIR);

      for (int var1 = -28; var1 <= 28; var1++) {
         for (int var2 = -28; var2 <= 28; var2++) {
            this.buildColumn(var1, var2);
         }
      }

      for (int var6 = 0; var6 < 4; var6++) {
         this.buildGate(var6);
         int[] var5;
         int var4 = (var5 = new int[]{-1, 1}).length;

         for (int var3 = 0; var3 < var4; var3++) {
            int var7 = var5[var3];
            this.buildButtress(var6, var7);
            this.buildFurnace(var6, var7);
         }
      }
   }

   public static void restore(ServerLevel var0) {
      BoundingBox var1 = new BoundingBox(-28, 60, -28, 28, 182, 28);
      var0.getBlockTicks().clearArea(var1);
      var0.getFluidTicks().clearArea(var1);
      MutableBlockPos var2 = new MutableBlockPos();
      int var3 = 0;

      for (int var4 = -28; var4 <= 28; var4++) {
         for (int var5 = -28; var5 <= 28; var5++) {
            for (int var6 = 60; var6 <= 182; var6++) {
               var2.set(var4, var6, var5);
               BlockState var7 = STRUCTURE.blocks[var3++];
               if (var0.getBlockState(var2) != var7) {
                  var0.setBlock(var2, var7, 818);
               }
            }
         }
      }
   }

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

            this.facing(var1, var2, 20, var4 - 3, HANGING_LANTERN);
         }

         if (var1 == 0 && var3 <= 4) {
            for (int var10 = 0; var10 < EMBLEM.length; var10++) {
               if (EMBLEM[var10].charAt(var2 + 4) == '#') {
                  this.facing(var1, var2, 24, 10 - var10, Blocks.SHROOMLIGHT);
                  this.facing(var1, var2, 23, 10 - var10, (Block)Blocks.STAINED_GLASS.red());
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
               this.facing(var1, var2 * var3, var6, var5, (Block)Blocks.STAINED_GLASS.red());
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
               this.facing(var1, var2 * var3, 20, var4, var3 == 11 ? Blocks.IRON_CHAIN : (Block)Blocks.STAINED_GLASS.red());
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
         case 0:
            this.put(var2, var4, var3, var5);
            break;
         case 1:
            this.put(var3, var4, -var2, var5);
            break;
         case 2:
            this.put(-var2, var4, -var3, var5);
            break;
         case 3:
            this.put(-var3, var4, var2, var5);
            break;
         default:
            throw new IllegalArgumentException("Ungültige Torseite");
      }
   }

   private void put(int var1, int var2, int var3, Block var4) {
      this.put(var1, var2, var3, var4.defaultBlockState());
   }

   private void put(int var1, int var2, int var3, BlockState var4) {
      int var5 = ((var1 + 28) * 57 + var3 + 28) * 123 + 64 + var2 - 60;
      this.blocks[var5] = var4;
   }
}
