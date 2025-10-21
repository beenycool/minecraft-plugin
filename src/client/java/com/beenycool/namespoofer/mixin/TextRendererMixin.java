package com.beenycool.namespoofer.mixin;

import com.beenycool.namespoofer.util.NameUtils;
import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(TextRenderer.class)
public class TextRendererMixin {
    @ModifyVariable(method = "drawInternal", at = @At("HEAD"), argsOnly = true, require = 0)
    private String nameSpoofer$modifyDrawnText(String original) {
        if (original == null || original.isEmpty() || !NameUtils.isEnabled()) {
            return original;
        }
        return NameUtils.apply(original);
    }
}
