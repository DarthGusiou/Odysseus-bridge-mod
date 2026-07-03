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

@Mixin(ChatHud.class)
public class ChatHudMixin {

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V",
            at = @At("HEAD"), require = 0)
    private void odysseus$capture_1(Text message, CallbackInfo ci) {
        odysseus$log("addMessage(Text)", message);
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), require = 0)
    private void odysseus$capture_3(Text message, MessageSignatureData sig, MessageIndicator ind, CallbackInfo ci) {
        odysseus$log("addMessage(Text,SigData,Indicator)", message);
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;ILnet/minecraft/client/gui/hud/MessageIndicator;Z)V",
            at = @At("HEAD"), require = 0)
    private void odysseus$capture_5(Text message, MessageSignatureData sig, int ticks, MessageIndicator ind, boolean refresh, CallbackInfo ci) {
        odysseus$log("addMessage(5arg)", message);
    }

    @Inject(method = "logChatMessage(Lnet/minecraft/text/Text;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), require = 0)
    private void odysseus$capture_log(Text message, MessageIndicator ind, CallbackInfo ci) {
        odysseus$log("logChatMessage", message);
    }

    private void odysseus$log(String via, Text message) {
        try {
            OdysseusBridge.LOG.info("[mixin] {} fired", via);
            if (message != null) OdysseusBridge.onChatMessage(message.getString());
        } catch (Throwable t) {
            OdysseusBridge.LOG.warn("[mixin] err {}: {}", via, t.toString());
        }
    }
}
