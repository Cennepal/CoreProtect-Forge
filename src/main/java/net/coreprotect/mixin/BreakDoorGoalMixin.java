package net.coreprotect.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.coreprotect.listener.MixinHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;

/**
 * Zombies breaking doors. Forge only fires its event when the mob starts breaking the door, not when the
 * door is actually removed.
 */
@Mixin(BreakDoorGoal.class)
public abstract class BreakDoorGoalMixin extends DoorInteractGoal {

    private BreakDoorGoalMixin(Mob mob) {
        super(mob);
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"), index = 0)
    private BlockPos coreprotect$onBreakDoor(BlockPos pos) {
        MixinHooks.mobBrokeDoor(this.mob, pos);
        return pos;
    }
}
