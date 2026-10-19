package net.coreprotect.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.coreprotect.listener.MixinHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.EnderMan;

@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanTakeBlockGoal")
public abstract class EndermanTakeBlockGoalMixin {

    @Shadow
    @Final
    private EnderMan enderman;

    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"), index = 0)
    private BlockPos coreprotect$onTake(BlockPos pos) {
        MixinHooks.endermanTake(enderman, pos);
        return pos;
    }
}
