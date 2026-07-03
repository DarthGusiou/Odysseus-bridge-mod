package com.odysseus.bridge.mixin;

import com.odysseus.bridge.OdysseusBridge;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks every message that lands in the client chat overlay — including
 * client-side-injected ones from Baritone, Meteor, etc. that never touch
 * the server → client receive events Fabric API exposes.
 *
 * Baritone's "[Baritone] Going to..." messages come in via this path;
 * OdysseusBridge decides what to forward.
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void odysseus$captureAllChat(Text message, CallbackInfo ci) {
        try {
            if (message != null) {
                OdysseusBridge.onChatMessage(message.getString());
            }
        } catch (Throwable t) {
            // never break the chat pipeline
        }
    }
}
