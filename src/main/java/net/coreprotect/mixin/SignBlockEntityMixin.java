package net.coreprotect.mixin;

import java.util.List;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.coreprotect.listener.MixinHooks;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;

@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {

    @Unique
    private boolean coreprotect$editAllowed;

    @Shadow
    public abstract boolean isWaxed();

    @Shadow
    public abstract UUID getPlayerWhoMayEdit();

    @Inject(method = "updateSignText", at = @At("HEAD"))
    private void coreprotect$beforeUpdate(Player player, boolean front, List<FilteredText> lines, CallbackInfo ci) {
        coreprotect$editAllowed = !isWaxed() && player.getUUID().equals(getPlayerWhoMayEdit()) && ((SignBlockEntity) (Object) this).getLevel() != null;
    }

    @Inject(method = "updateSignText", at = @At("TAIL"))
    private void coreprotect$afterUpdate(Player player, boolean front, List<FilteredText> lines, CallbackInfo ci) {
        if (coreprotect$editAllowed) {
            coreprotect$editAllowed = false;
            MixinHooks.signEdited((SignBlockEntity) (Object) this, player, front);
        }
    }
}
