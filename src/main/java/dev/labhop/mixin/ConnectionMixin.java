package dev.labhop.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.labhop.Lab;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;

@Mixin(Connection.class)
public abstract class ConnectionMixin {

	/** While in the lab, packets from the real server are buffered instead of handled. */
	@Inject(method = "channelRead0", at = @At("HEAD"), cancellable = true)
	private void labhop$intercept(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
		if (Lab.intercept(this, packet)) ci.cancel();
	}

	/** Vanilla closes the old connection when a world is loaded. Don't, while it is parked. */
	@Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"), cancellable = true)
	private void labhop$keepAlive(DisconnectionDetails details, CallbackInfo ci) {
		if (Lab.isProtected(this)) ci.cancel();
	}
}
