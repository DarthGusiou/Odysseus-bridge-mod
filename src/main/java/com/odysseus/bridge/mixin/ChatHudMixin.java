package com.odysseus.bridge.mixin;

import com.odysseus.bridge.OdysseusBridge;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts every message being added to the chat overlay.
 *
 * Two paths:
 *   1. Structured Odysseus events (Baritone → [ODY] prefix + JSON envelope).
 *      Detected first; parsed, forwarded to Odysseus, and rendering is
 *      cancelled so the raw JSON never shows in the chat overlay.
 *   2. Legacy Baritone chat (path status, error strings, etc.) — passed to
 *      OdysseusBridge.onChatMessage which pattern-matches and forwards.
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), require = 0, cancellable = true)
    private void odysseus$captureChat3(Text message, MessageSignatureData sig, MessageIndicator ind, CallbackInfo ci) {
        try {
            if (message == null) return;
            String text = message.getString();
            if (OdysseusBridge.tryHandleStructuredEvent(text)) {
                ci.cancel();
                return;
            }
            OdysseusBridge.onChatMessage(text);
        } catch (Throwable t) {
            OdysseusBridge.LOG.warn("[mixin] err3: {}", t.toString());
        }
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V",
            at = @At("HEAD"), require = 0, cancellable = true)
    private void odysseus$captureChat1(Text message, CallbackInfo ci) {
        try {
            if (message == null) return;
            String text = message.getString();
            if (OdysseusBridge.tryHandleStructuredEvent(text)) {
                ci.cancel();
                return;
            }
            OdysseusBridge.onChatMessage(text);
        } catch (Throwable t) {
            OdysseusBridge.LOG.warn("[mixin] err1: {}", t.toString());
        }
    }
}
