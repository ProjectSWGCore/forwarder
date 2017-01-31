package com.projectswg.networking.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.SocketChannel;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import network.PacketType;
import network.packets.swg.holo.HoloConnectionStarted;
import network.packets.swg.holo.HoloConnectionStopped;
import network.packets.swg.holo.HoloSetProtocolVersion;
import network.packets.swg.holo.HoloConnectionStopped.ConnectionStoppedReason;

import com.projectswg.control.Assert;
import com.projectswg.networking.server.SWGProtocol.RawPacket;
import com.projectswg.resources.ServerConnectionStatus;
import com.projectswg.utilities.Log;

public class HolocoreSocket {
	
	private static final String PROTOCOL = "2016-04-13";
	
	private final Object socketMutex;
	private final ByteBuffer buffer;
	private final SWGProtocol swgProtocol;
	private final AtomicReference<ServerConnectionStatus> status;
	private SocketChannel socket;
	private StatusChangedCallback callback;
	private InetAddress addr;
	private int port;
	
	public HolocoreSocket(InetAddress addr, int port) {
		this.socketMutex = new Object();
		this.buffer = ByteBuffer.allocateDirect(128*1024);
		this.swgProtocol = new SWGProtocol();
		this.socket = null;
		this.status = new AtomicReference<>(ServerConnectionStatus.DISCONNECTED);
		this.callback = null;
		this.addr = addr;
		this.port = port;
	}
	
	public void setStatusChangedCallback(StatusChangedCallback callback) {
		this.callback = callback;
	}
	
	public void setRemoteAddress(InetAddress addr, int port) {
		this.addr = addr;
		this.port = port;
	}
	
	public InetSocketAddress getRemoteAddress() {
		return new InetSocketAddress(addr, port);
	}
	
	public ServerConnectionStatus getConnectionState() {
		return status.get();
	}
	
	public boolean isDisconnected() {
		return status.get() == ServerConnectionStatus.DISCONNECTED;
	}
	
	public boolean isConnecting() {
		return status.get() == ServerConnectionStatus.CONNECTING;
	}
	
	public boolean isConnected() {
		return status.get() == ServerConnectionStatus.CONNECTED;
	}
	
	public boolean connect() {
		synchronized (socketMutex) {
			Assert.test(isDisconnected());
			try {
				Log.out(this, "Connecting to %s at port %d", addr, port);
				swgProtocol.reset();
				socket = SocketChannel.open();
				updateStatus(ServerConnectionStatus.CONNECTING, ServerConnectionChangedReason.NONE);
				socket.socket().setKeepAlive(true);
				socket.socket().setPerformancePreferences(0, 1, 2);
				socket.socket().setTrafficClass(0x10); // Low Delay bit
				socket.configureBlocking(true);
				socket.connect(new InetSocketAddress(addr, port));
				if (!socket.finishConnect())
					return false;
				waitForConnect();
				Log.out(this, "Connected to Server");
				return true;
			} catch (IOException e) {
				if (e instanceof AsynchronousCloseException) {
					disconnect(ServerConnectionChangedReason.SOCKET_CLOSED);
				} else if (e.getMessage() == null) {
					disconnect(ServerConnectionChangedReason.UNKNOWN);
					Log.err(this, e);
				} else {
					disconnect(getReason(e.getMessage()));
				}
				return false;
			}
		}
	}
	
	public boolean disconnect(ServerConnectionChangedReason reason) {
		synchronized (socketMutex) {
			if (isDisconnected())
				return true;
			Assert.notNull(socket);
			updateStatus(ServerConnectionStatus.DISCONNECTED, reason);
			Log.out(this, "Disconnected from server with reason %s", reason);
			try {
				if (socket.isOpen())
					socket.write(swgProtocol.assemble(new HoloConnectionStopped(ConnectionStoppedReason.APPLICATION).encode().array()));
				socket.close();
				socket = null;
				return true;
			} catch (IOException e) {
				Log.err(this, e);
				return false;
			}
		}
	}
	
	public boolean send(byte [] raw) {
		return sendRaw(swgProtocol.assemble(raw));
	}
	
	public RawPacket read() {
		RawPacket packet = null;
		do {
			packet = swgProtocol.disassemble();
			if (packet != null)
				return packet;
			readRaw(buffer);
			swgProtocol.addToBuffer(buffer);
		} while (!isDisconnected());
		return null;
	}
	
	private boolean sendRaw(ByteBuffer data) {
		if (isDisconnected()) {
			Log.err(this, "Cannot send! Not connected.");
			return false;
		}
		try {
			synchronized (socketMutex) {
				socket.write(data);
			}
			return true;
		} catch (IOException e) {
			Log.err(this, e);
			disconnect(ServerConnectionChangedReason.OTHER_SIDE_TERMINATED);
		}
		return false;
	}
	
	private boolean readRaw(ByteBuffer data) {
		try {
			data.position(0);
			data.limit(data.capacity());
			int n = socket.read(data);
			if (n < 0) {
				disconnect(ServerConnectionChangedReason.OTHER_SIDE_TERMINATED);
			} else {
				data.flip();
				return true;
			}
		} catch (Exception e) {
			if (e instanceof AsynchronousCloseException) {
				disconnect(ServerConnectionChangedReason.SOCKET_CLOSED);
			} else if (e.getMessage() != null) {
				disconnect(getReason(e.getMessage()));
			} else {
				disconnect(ServerConnectionChangedReason.UNKNOWN);
				Log.err(this, e);
			}
		}
		return false;
	}
	
	private void waitForConnect() {
		send(new HoloSetProtocolVersion(PROTOCOL).encode().array());
		while (isConnecting()) {
			RawPacket packet = read();
			if (packet == null)
				continue;
			handlePacket(packet.getPacketType(), packet.getData());
		}
		if (isConnected())
			send(new HoloConnectionStarted().encode().array());
	}
	
	private void handlePacket(PacketType type, byte [] raw) {
		if (type == PacketType.HOLO_CONNECTION_STARTED) {
			updateStatus(ServerConnectionStatus.CONNECTED, ServerConnectionChangedReason.NONE);
		} else if (type == PacketType.HOLO_CONNECTION_STOPPED) {
			HoloConnectionStopped packet = new HoloConnectionStopped();
			packet.decode(ByteBuffer.wrap(raw));
			switch (packet.getReason()) {
				case INVALID_PROTOCOL:
					disconnect(ServerConnectionChangedReason.INVALID_PROTOCOL);
					break;
				default:
					disconnect(ServerConnectionChangedReason.NONE);
					break;
			}
		}
	}
	
	private void updateStatus(ServerConnectionStatus status, ServerConnectionChangedReason reason) {
		ServerConnectionStatus old = this.status.getAndSet(status);
		Log.out(this, "Server Status: %s -> %s", old, status);
		if (old != status && callback != null)
			callback.onConnectionStatusChanged(old, status, reason);
	}
	
	private ServerConnectionChangedReason getReason(String message) {
		message = message.toLowerCase(Locale.US);
		if (message.contains("broken pipe"))
			return ServerConnectionChangedReason.BROKEN_PIPE;
		if (message.contains("connection reset"))
			return ServerConnectionChangedReason.CONNECTION_RESET;
		if (message.contains("connection refused"))
			return ServerConnectionChangedReason.CONNECTION_REFUSED;
		if (message.contains("address in use"))
			return ServerConnectionChangedReason.ADDR_IN_USE;
		if (message.contains("socket closed"))
			return ServerConnectionChangedReason.SOCKET_CLOSED;
		if (message.contains("no route to host"))
			return ServerConnectionChangedReason.NO_ROUTE_TO_HOST;
		Log.err(this, "Unknown reason: " + message);
		return ServerConnectionChangedReason.UNKNOWN;
	}
	
	public interface StatusChangedCallback {
		void onConnectionStatusChanged(ServerConnectionStatus oldStatus, ServerConnectionStatus newStatus, ServerConnectionChangedReason reason);
	}
	
}
