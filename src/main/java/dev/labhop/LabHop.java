package dev.labhop;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class LabHop implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("lab").executes(ctx -> {
				Lab.request(); // handled at the end of the tick, not inside the chat screen
				return 1;
			})));

		ClientTickEvents.END_CLIENT_TICK.register(Lab::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> Lab.onJoin(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Lab.onDisconnect(client));
	}
}
