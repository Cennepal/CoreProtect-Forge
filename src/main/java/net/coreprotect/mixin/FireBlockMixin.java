package net.coreprotect.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.coreprotect.listener.MixinHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Unique
    private static BlockState coreprotect$burning;

    @Inject(method = "tryCatchFire", at = @At("HEAD"), remap = false)
    private void coreprotect$beforeBurn(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face, CallbackInfo ci) {
        coreprotect$burning = level.getBlockState(pos);
    }

    @Inject(method = "tryCatchFire", at = @At("RETURN"), remap = false)
    private void coreprotect$afterBurn(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face, CallbackInfo ci) {
        BlockState before = coreprotect$burning;
        coreprotect$burning = null;
        if (before != null) {
            MixinHooks.blockBurned(level, pos, before);
        }
    }
}
