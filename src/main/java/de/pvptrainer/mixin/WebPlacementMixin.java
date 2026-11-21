package de.pvptrainer.mixin;

import de.pvptrainer.session.ArenaSignals;
import de.pvptrainer.combat.CrystalCover;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
abstract class WebPlacementMixin {
    @Inject(method="placeBlock",at=@At("RETURN"))
    private void pvptrainer$placed(BlockPlaceContext context,BlockState block,CallbackInfoReturnable<Boolean> placed){
        if(!placed.getReturnValue() || !(context.getPlayer() instanceof ServerPlayer player))return;
        if(block.is(Blocks.COBWEB))ArenaSignals.web(player,context.getClickedPos());
        else if(block.is(Blocks.OBSIDIAN))CrystalCover.playerPlaced(player,context.getClickedPos());
    }
}
