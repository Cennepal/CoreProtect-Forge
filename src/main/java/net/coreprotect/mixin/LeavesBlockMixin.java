package net.coreprotect.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.coreprotect.listener.MixinHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {

    @Shadow
    protected abstract boolean decaying(BlockState state);

    @Inject(method = "randomTick", at = @At("HEAD"))
    private void coreprotect$onRandomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (decaying(state)) {
            MixinHooks.leafDecay(level, pos, state);
        }
    }
}
