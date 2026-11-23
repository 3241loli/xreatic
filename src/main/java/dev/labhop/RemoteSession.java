package dev.labhop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Everything belonging to the real server connection while the player is in the lab:
 * the old level/player objects, plus a buffer for packets the server sends meanwhile.
 */
final class RemoteSession {
	private static final int MAX_BUFFERED = 60_000;

	final Connection connection;
	final ClientPacketListener listener;
	final ClientLevel level;
	final LocalPlayer player;
	final MultiPlayerGameMode gameMode;
	final ServerData serverData;

	// position the server last knew, used for the heartbeat
	final double x, y, z;
	final float yRot, xRot;
	final boolean onGround, horizontalCollision;

	/** While true, Connection.disconnect() on the held connection is ignored. */
	volatile boolean protect = true;
	volatile boolean overflow = false;

	private final Object lock = new Object();
	private final ArrayDeque<Packet<?>> queue = new ArrayDeque<>();
	private boolean intercepting = true;

	RemoteSession(Minecraft mc) {
		this.listener = mc.getConnection();
		this.connection = listener.getConnection();
		this.level = mc.level;
		this.player = mc.player;
		this.gameMode = mc.gameMode;
		this.serverData = mc.getCurrentServer();
		this.x = player.getX();
		this.y = player.getY();
		this.z = player.getZ();
		this.yRot = player.getYRot();
		this.xRot = player.getXRot();
		this.onGround = player.onGround();
		this.horizontalCollision = player.horizontalCollision;
	}

	/** Called on the netty thread for every incoming packet. Returns true if the packet was swallowed. */
	boolean offer(Packet<?> packet) {
		synchronized (lock) {
			if (!intercepting) return false;

			// answer these right away so the server does not time us out
			if (packet instanceof ClientboundKeepAlivePacket ka) {
				connection.send(new ServerboundKeepAlivePacket(ka.getId()));
				return true;
			}
			if (packet instanceof ClientboundPingPacket ping) {
				connection.send(new ServerboundPongPacket(ping.getId()));
				return true;
			}
			if (queue.size() >= MAX_BUFFERED) {
				overflow = true;
				return true;
			}
			queue.add(packet);
			return true;
		}
	}

	/** Takes up to n buffered packets. When the queue is empty, interception ends atomically. */
	List<Packet<?>> takeBatch(int n) {
		synchronized (lock) {
			List<Packet<?>> out = new ArrayList<>();
			while (out.size() < n && !queue.isEmpty()) out.add(queue.poll());
			if (out.isEmpty()) intercepting = false;
			return out;
		}
	}

	boolean finished() {
		synchronized (lock) {
			return !intercepting && queue.isEmpty();
		}
	}

	void stopIntercepting() {
		synchronized (lock) {
			intercepting = false;
			queue.clear();
		}
	}

	void heartbeat() {
		connection.send(new ServerboundMovePlayerPacket.PosRot(x, y, z, yRot, xRot, onGround, horizontalCollision));
	}
}
