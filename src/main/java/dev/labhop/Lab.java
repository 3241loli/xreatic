package dev.labhop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;

public final class Lab {
	private static final Logger LOG = LoggerFactory.getLogger("LabHop");
	private static final String WORLD = "labhop_lab";
	private static final int ENTER_TIMEOUT_TICKS = 20 * 60;
	private static final int HEARTBEAT_TICKS = 10;
	private static final int REPLAY_PER_TICK = 300;

	enum Phase { NONE, ENTERING, LAB, REPLAY }

	private static Phase phase = Phase.NONE;
	private static RemoteSession session;
	private static boolean requested;
	private static int ticks;

	private Lab() {}

	// ---- used by the mixins ----

	/** True while the real server connection is parked. */
	public static boolean active() {
		return session != null;
	}

	/** True if this connection is the parked server connection and must not be closed right now. */
	public static boolean isProtected(Object connection) {
		RemoteSession s = session;
		return s != null && s.protect && s.connection == connection;
	}

	/** Netty thread. Returns true if the packet was swallowed into the buffer. */
	public static boolean intercept(Object connection, Packet<?> packet) {
		RemoteSession s = session;
		return s != null && s.connection == connection && s.offer(packet);
	}

	// ---- events ----

	static void request() {
		requested = true;
	}

	static void tick(Minecraft mc) {
		if (requested) {
			requested = false;
			toggle(mc);
		}
		RemoteSession s = session;
		if (s == null) return;

		if (phase == Phase.ENTERING || phase == Phase.LAB) {
			if (++ticks % HEARTBEAT_TICKS == 0) s.heartbeat();
			if (phase == Phase.ENTERING && ticks > ENTER_TIMEOUT_TICKS) {
				LOG.error("Lab did not load in time, going back to the server");
				abortEnter(mc);
			}
		} else if (phase == Phase.REPLAY) {
			replay(mc, s);
		}
	}

	static void onJoin(Minecraft mc) {
		if (phase == Phase.ENTERING && mc.isLocalServer()) {
			phase = Phase.LAB;
			say(mc, "You are in the lab. Type /lab again to return to the server.");
		}
	}

	static void onDisconnect(Minecraft mc) {
		// The player left the lab by other means (Save and Quit): close the parked connection too
		if (phase == Phase.LAB || phase == Phase.ENTERING) {
			closeRemote(mc);
		}
	}

	// ---- state machine ----

	private static void toggle(Minecraft mc) {
		if (phase == Phase.NONE) enter(mc);
		else if (phase == Phase.LAB) leave(mc);
		else say(mc, "LabHop is busy, try again in a moment.");
	}

	private static void enter(Minecraft mc) {
		if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
		if (mc.isLocalServer()) {
			say(mc, "/lab only works while you are connected to a server.");
			return;
		}

		say(mc, "Hopping into the lab... (stand somewhere safe, you stay in the server world)");
		RemoteSession s = new RemoteSession(mc); // from here on incoming packets are buffered
		session = s;
		phase = Phase.ENTERING;
		ticks = 0;

		try {
			mc.runAllTasks(); // finish packet handlers that were already scheduled
			deleteLabWorld(mc);

			// detach the server world so vanilla's teardown cannot touch it
			mc.level = null;
			mc.player = null;
			mc.gameMode = null;

			// NOTE: if this constructor does not compile, see the alternative at the bottom of this file
			LevelSettings settings = new LevelSettings(
				WORLD, GameType.CREATIVE,
				new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
				true, WorldDataConfiguration.DEFAULT);

			mc.createWorldOpenFlows().createFreshLevel(
				WORLD, settings, WorldOptions.defaultWithRandomSeed(),
				registries -> registries.lookupOrThrow(Registries.WORLD_PRESET)
					.getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
				new TitleScreen());
		} catch (Throwable t) {
			LOG.error("Could not start the lab", t);
			abortEnter(mc);
		}
	}

	private static void leave(Minecraft mc) {
		RemoteSession s = session;
		if (s == null) return;
		phase = Phase.REPLAY;
		try {
			if (mc.level != null) mc.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
			// 'true' = keep server resource packs
			mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")), true);
		} catch (Throwable t) {
			LOG.error("Error while closing the lab world", t);
		}
		deleteLabWorld(mc);

		restore(mc, s);
		s.protect = false;
		if (s.overflow) {
			s.connection.disconnect(Component.literal("LabHop: you were away too long (packet buffer full)"));
		} else {
			say(mc, "Back on the server.");
		}
	}

	private static void abortEnter(Minecraft mc) {
		RemoteSession s = session;
		if (s == null) return;
		try {
			mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")), true);
		} catch (Throwable t) {
			LOG.error("Error while aborting", t);
		}
		deleteLabWorld(mc);
		phase = Phase.REPLAY;
		restore(mc, s);
		s.protect = false;
		say(mc, "Could not open the lab, you are back on the server.");
	}

	private static void closeRemote(Minecraft mc) {
		RemoteSession s = session;
		session = null;
		phase = Phase.NONE;
		if (s == null) return;
		s.protect = false;
		s.stopIntercepting();
		s.connection.disconnect(Component.literal("Left the server while in the lab"));
		mc.gui.getChat().clearMessages(false);
	}

	private static void replay(Minecraft mc, RemoteSession s) {
		List<Packet<?>> batch = s.takeBatch(REPLAY_PER_TICK);
		ClientPacketListener listener = s.listener;
		for (Packet<?> p : batch) {
			if (mc.level != s.level) break; // we were kicked/disconnected meanwhile
			try {
				@SuppressWarnings("unchecked")
				Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> packet =
					(Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>) p;
				packet.handle(listener);
			} catch (Throwable t) {
				LOG.warn("Error while replaying a buffered packet", t);
			}
		}
		if (s.finished() || !s.connection.isConnected()) {
			s.stopIntercepting();
			session = null;
			phase = Phase.NONE;
		}
	}

	/** Puts the server world back into the client. */
	private static void restore(Minecraft mc, RemoteSession s) {
		mc.level = s.level;
		mc.player = s.player;
		mc.gameMode = s.gameMode;
		mc.setCurrentServer(s.serverData);
		mc.setCameraEntity(s.player);
		mc.levelRenderer.setLevel(s.level);
		mc.particleEngine.setLevel(s.level);
		mc.getEntityRenderDispatcher().setLevel(s.level);
		mc.getBlockEntityRenderDispatcher().setLevel(s.level);
		mc.levelRenderer.allChanged();
		mc.setScreen(null);
	}

	private static void deleteLabWorld(Minecraft mc) {
		try {
			Path dir = mc.getLevelSource().getBaseDir().resolve(WORLD);
			if (!Files.exists(dir)) return;
			try (LevelStorageSource.LevelStorageAccess access = mc.getLevelSource().createAccess(WORLD)) {
				access.deleteLevel();
			}
		} catch (Exception e) {
			LOG.warn("Could not delete the old lab world", e);
		}
	}

	private static void say(Minecraft mc, String text) {
		if (mc.player != null) mc.player.displayClientMessage(Component.literal("[LabHop] " + text), false);
	}

	/*
	 * ALTERNATIVE if "new LevelSettings(...DifficultySettings...)" does not exist in 1.21.11:
	 *
	 *   LevelSettings settings = new LevelSettings(
	 *       WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
	 *       new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT);
	 */
}
