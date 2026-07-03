package com.odysseus.bridge.mixin;

import com.odysseus.bridge.OdysseusBridge;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public class ChatHudMixin {

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), require = 0)
    private void odysseus$captureAddMessageSingle(Text message, CallbackInfo ci) {
        try {
            OdysseusBridge.LOG.info("[mixin] addMessage(Text) fired");
            if (message != null) OdysseusBridge.onChatMessage(message.getString());
        } catch (Throwable t) {
            OdysseusBridge.LOG.warn("[mixin] error in single-arg", t);
        }
    }

    @Inject(method = "logChatMessage(Lnet/minecraft/text/Text;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), require = 0)
    private void odysseus$captureLogChatMessage(Text message, Object indicator, CallbackInfo ci) {
        try {
            OdysseusBridge.LOG.info("[mixin] logChatMessage fired");
            if (message != null) OdysseusBridge.onChatMessage(message.getString());
        } catch (Throwable t) {
            OdysseusBridge.LOG.warn("[mixin] error in logChatMessage", t);
        }
    }
}
