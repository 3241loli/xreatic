package dev.labhop.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.labhop.Lab;
import net.minecraft.client.gui.components.ChatComponent;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

	/** Keep the server chat when the lab world is closed. */
	@Inject(method = "clearMessages", at = @At("HEAD"), cancellable = true)
	private void labhop$keepChat(boolean clearHistory, CallbackInfo ci) {
		if (Lab.active()) ci.cancel();
	}
}
