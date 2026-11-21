package de.pvptrainer.combat;
import de.pvptrainer.analytics.BotTelemetry;
import de.pvptrainer.TrainerClient;
import de.pvptrainer.arena.TrainingArena;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Only owner-placed obsidian observed during this duel can be mined. */
public final class CrystalCover {
    private final Set<BlockPos> playerObsidian = new HashSet<>();
    private Vec3 lastSeen;
    private BlockPos mining;
    private float progress;
    private int lastStage=-1;
    private ServerLevel world;
    private int actor;

    public static void playerPlaced(ServerPlayer player,BlockPos pos){
        if(!TrainingArena.isArena(player.level()))return;
        var session=TrainerClient.session(player.level().getServer());
        if(session!=null)session.sawPlayerObsidian(player,pos);
    }

    public void added(BlockPos pos,LivingEntity bot){
        Vec3 eye=bot.getEyePosition(),point=Vec3.atCenterOf(pos);
        if(eye.distanceToSqr(point)>100 || !CrystalPursuit.inFront(bot,point))return;
        var hit=bot.level().clip(new ClipContext(eye,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,bot));
        if(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(pos))playerObsidian.add(pos.immutable());
    }

    void observe(ServerPlayer opponent) {
        lastSeen=opponent.getEyePosition();
    }

    BlockPos workingBlock() { return mining; }

    boolean tick(ServerLevel level,LivingEntity bot,ServerPlayer opponent,BotEquipment kit,boolean visible){
        if(visible){cancel();return false;}
        if(lastSeen==null || playerObsidian.isEmpty() || bot.isUsingItem()){cancel();return false;}
        Vec3 eye=bot.getEyePosition();
        var hit=level.clip(new ClipContext(eye,lastSeen,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,bot));
        var pos=hit.getBlockPos();var state=level.getBlockState(pos);
        if(hit.getType()!=HitResult.Type.BLOCK || hit.getLocation().distanceToSqr(eye)>20.25 || !playerObsidian.contains(pos)
                || !state.is(Blocks.OBSIDIAN) || !CrystalPursuit.inFront(bot,hit.getLocation())){cancel();return false;}
        if(!kit.equipWeapon(bot,Items.NETHERITE_PICKAXE)&&!kit.equipWeapon(bot,Items.DIAMOND_PICKAXE)){cancel();return false;}
        ItemStack tool=bot.getMainHandItem();
        if(!pos.equals(mining)){cancel();mining=pos.immutable();world=level;actor=bot.getId();}
        progress+=speed(level,bot,tool,state)/state.getDestroySpeed(level,pos)/(tool.isCorrectToolForDrops(state)?30:100);
        if(!bot.swinging)bot.swing(InteractionHand.MAIN_HAND,true);
        if(progress>=1){
            if(level.destroyBlock(pos,false,bot,512)){tool.hurtAndBreak(1,bot,EquipmentSlot.MAINHAND);BotTelemetry.action(level,bot,"MINE");}
            playerObsidian.remove(pos);cancel();
        }else{
            int stage=(int)(progress*10);
            if(stage!=lastStage){world.destroyBlockProgress(actor,pos,stage);lastStage=stage;}
        }
        return true;
    }

    private static float speed(ServerLevel level,LivingEntity bot,ItemStack tool,BlockState state){
        float speed=tool.getDestroySpeed(state);
        var ench=level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);
        int efficiency=EnchantmentHelper.getItemEnchantmentLevel(ench,tool);
        if(speed>1 && efficiency>0)speed+=efficiency*efficiency+1;
        var haste=bot.getEffect(MobEffects.HASTE);
        if(haste!=null)speed*=1+.2F*(1+haste.getAmplifier());
        var fatigue=bot.getEffect(MobEffects.MINING_FATIGUE);
        if(fatigue!=null)speed*=switch(fatigue.getAmplifier()){case 0->.3F;case 1->.09F;case 2->.0027F;default->.00081F;};
        if(bot.isEyeInFluid(FluidTags.WATER))speed*=bot.getAttribute(Attributes.SUBMERGED_MINING_SPEED)!=null?bot.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED):.2;
        return bot.onGround()?speed:speed/5;
    }

    void cancel(){
        if(mining!=null)world.destroyBlockProgress(actor,mining,-1);
        mining=null;progress=0;lastStage=-1;
    }
}
