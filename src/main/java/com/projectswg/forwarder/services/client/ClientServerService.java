package com.projectswg.forwarder.services.client;

import com.projectswg.forwarder.Forwarder.ForwarderData;
import com.projectswg.forwarder.intents.client.*;
import com.projectswg.forwarder.intents.control.StartForwarderIntent;
import com.projectswg.forwarder.intents.control.StopForwarderIntent;
import com.projectswg.forwarder.intents.server.RequestServerConnectionIntent;
import com.projectswg.forwarder.intents.server.ServerConnectedIntent;
import com.projectswg.forwarder.intents.server.ServerDisconnectedIntent;
import com.projectswg.forwarder.resources.networking.ClientServer;
import com.projectswg.forwarder.resources.networking.data.ProtocolStack;
import com.projectswg.forwarder.resources.networking.packets.*;
import com.projectswg.forwarder.resources.networking.packets.Disconnect.DisconnectReason;
import me.joshlarson.jlcommon.control.IntentChain;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.Service;
import me.joshlarson.jlcommon.log.Log;
import me.joshlarson.jlcommon.network.UDPServer;
import me.joshlarson.jlcommon.utilities.ByteUtilities;

import java.net.*;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ClientServerService extends Service {
	
	private final IntentChain intentChain;
	private final Map<ClientServer, ProtocolStack> stacks;
	private final AtomicBoolean serverConnection;
	private final AtomicReference<SessionRequest> cachedSessionRequest;
	private final AtomicReference<InetSocketAddress> lastPing;
	
	private ForwarderData data;
	private UDPServer loginServer;
	private UDPServer zoneServer;
	private UDPServer pingServer;
	
	public ClientServerService() {
		this.intentChain = new IntentChain();
		this.stacks = new EnumMap<>(ClientServer.class);
		this.serverConnection = new AtomicBoolean(false);
		this.cachedSessionRequest = new AtomicReference<>(null);
		this.lastPing = new AtomicReference<>(null);
		this.data = null;
		this.loginServer = null;
		this.zoneServer = null;
		this.pingServer = null;
	}
	
	@Override
	public boolean isOperational() {
		if (data == null)
			return true;
		if (loginServer == null || !loginServer.isRunning())
			return false;
		if (zoneServer == null || !zoneServer.isRunning())
			return false;
		return pingServer != null && pingServer.isRunning();
	}
	
	@Override
	public boolean stop() {
		closeConnection(ClientServer.LOGIN);
		closeConnection(ClientServer.ZONE);
		return true;
	}
	
	@IntentHandler
	private void handleStartForwarderIntent(StartForwarderIntent sfi) {
		try {
			ForwarderData data = sfi.getData();
			Log.t("Initializing login udp server...");
			loginServer = new UDPServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), data.getLoginPort()), 496, this::onLoginPacket);
			Log.t("Initializing zone udp server...");
			zoneServer = new UDPServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), data.getZonePort()), 496, this::onZonePacket);
			Log.t("Initializing ping udp server...");
			pingServer = new UDPServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), data.getPingPort()), 496, this::onPingPacket);
			
			Log.t("Binding to login server...");
			loginServer.bind(this::customizeUdpServer);
			Log.t("Binding to zone server...");
			zoneServer.bind(this::customizeUdpServer);
			Log.t("Binding to ping server...");
			pingServer.bind(this::customizeUdpServer);
			
			data.setLoginPort(loginServer.getPort());
			data.setZonePort(zoneServer.getPort());
			data.setPingPort(pingServer.getPort());
			
			Log.i("Initialized login (%d), zone (%d), and ping (%d) servers", loginServer.getPort(), zoneServer.getPort(), pingServer.getPort());
		} catch (SocketException e) {
			Log.a(e);
			if (loginServer != null)
				loginServer.close();
			if (zoneServer != null)
				zoneServer.close();
			if (pingServer != null)
				pingServer.close();
			loginServer = null;
			zoneServer = null;
			pingServer = null;
		}
		data = sfi.getData();
	}
	
	@IntentHandler
	private void handleStopForwarderIntent(StopForwarderIntent sfi) {
		Log.t("Closing the login udp server...");
		if (loginServer != null)
			loginServer.close();
		Log.t("Closing the zone udp server...");
		if (zoneServer != null)
			zoneServer.close();
		Log.t("Closing the ping udp server...");
		if (pingServer != null)
			pingServer.close();
		loginServer = null;
		zoneServer = null;
		pingServer = null;
		Log.i("Closed the login, zone, and ping udp servers");
	}
	
	@IntentHandler
	private void handleServerConnectedIntent(ServerConnectedIntent sci) {
		serverConnection.set(true);
		SessionRequest request = this.cachedSessionRequest.getAndSet(null);
		if (request != null) {
			byte [] data = request.encode().array();
			onLoginPacket(new DatagramPacket(data, data.length, new InetSocketAddress(request.getAddress(), request.getPort())));
		}
	}
	
	@IntentHandler
	private void handleServerDisconnectedIntent(ServerDisconnectedIntent sdi) {
		serverConnection.set(false);
		closeConnection(ClientServer.LOGIN);
		closeConnection(ClientServer.ZONE);
		closeConnection(ClientServer.PING);
	}
	
	@IntentHandler
	private void handleSendPongIntent(SendPongIntent spi) {
		InetSocketAddress lastPing = this.lastPing.get();
		if (lastPing != null)
			send(lastPing, ClientServer.PING, spi.getData());
	}
	
	private void customizeUdpServer(DatagramSocket socket) {
		try {
			socket.setReuseAddress(false);
			socket.setTrafficClass(0x10);
			socket.setBroadcast(false);
			socket.setReceiveBufferSize(64 * 1024);
			socket.setSendBufferSize(64 * 1024);
		} catch (SocketException e) {
			Log.w(e);
		}
	}
	
	private void onLoginPacket(DatagramPacket packet) {
		process((InetSocketAddress) packet.getSocketAddress(), ClientServer.LOGIN, packet.getData());
	}
	
	private void onZonePacket(DatagramPacket packet) {
		process((InetSocketAddress) packet.getSocketAddress(), ClientServer.ZONE, packet.getData());
	}
	
	private void onPingPacket(DatagramPacket packet) {
		InetSocketAddress source = (InetSocketAddress) packet.getSocketAddress();
		lastPing.set(source);
		ProtocolStack stack = stacks.get(ClientServer.ZONE);
		PingPacket pingPacket = new PingPacket(packet.getData());
		pingPacket.setAddress(source.getAddress());
		pingPacket.setPort(source.getPort());
		
		if (stack != null)
			intentChain.broadcastAfter(getIntentManager(), new SonyPacketInboundIntent(stack, pingPacket));
	}
	
	private void send(InetSocketAddress addr, ClientServer server, byte [] data) {
		switch (server) {
			case LOGIN:
				loginServer.send(addr, data);
				break;
			case ZONE:
				zoneServer.send(addr, data);
				break;
			case PING:
				pingServer.send(addr, data);
				break;
		}
	}
	
	private void process(InetSocketAddress source, ClientServer server, byte [] data) {
		Packet parsed;
		try {
			parsed = (server == ClientServer.PING) ? new PingPacket(data) : parse(data);
			if (parsed == null)
				return;
		} catch (BufferUnderflowException e) {
			Log.w("Failed to parse packet: %s", ByteUtilities.getHexString(data));
			return;
		}
		parsed.setAddress(source.getAddress());
		parsed.setPort(source.getPort());
		if (parsed instanceof MultiPacket) {
			for (byte [] child : ((MultiPacket) parsed).getPackets()) {
				process(source, server, child);
			}
		} else {
			broadcast(source, server, parsed);
		}
	}
	
	private void broadcast(InetSocketAddress source, ClientServer server, Packet parsed) {
		ProtocolStack stack = process(source, server, parsed);
		if (stack == null) {
			Log.t("[%s]@%s DROPPED %s", source, server, parsed);
			return;
		}
		Log.t("[%s]@%s sent: %s", source, server, parsed);
		intentChain.broadcastAfter(getIntentManager(), new SonyPacketInboundIntent(stack, parsed));
	}
	
	private ProtocolStack process(InetSocketAddress source, ClientServer server, Packet parsed) {
		Log.t("Process [%b] %s", serverConnection.get(), parsed);
		if (!serverConnection.get()) {
			if (parsed instanceof SessionRequest) {
				cachedSessionRequest.set((SessionRequest) parsed);
				intentChain.broadcastAfter(getIntentManager(), new RequestServerConnectionIntent());
			}
			for (ClientServer serverType : ClientServer.values())
				closeConnection(serverType);
			return null;
		}
		if (parsed instanceof SessionRequest)
			return onSessionRequest(source, server, (SessionRequest) parsed);
		if (parsed instanceof Disconnect)
			return onDisconnect(source, server, (Disconnect) parsed);
		
		ProtocolStack stack = stacks.get(server);
		if (stack == null)
			return null;
		
		if (parsed instanceof PingPacket) {
			stack.setPingSource(source);
		} else if (!stack.getSource().equals(source) || stack.getServer() != server) {
			return null;
		}
		
		return stack;
	}
	
	private ProtocolStack onSessionRequest(InetSocketAddress source, ClientServer server, SessionRequest request) {
		ProtocolStack current = stacks.get(server);
		if (current != null && request.getConnectionId() != current.getConnectionId()) {
			closeConnection(server);
			return null;
		}
		ProtocolStack stack = new ProtocolStack(source, server, (remote, data) -> send(remote, server, data));
		stack.setConnectionId(request.getConnectionId());
		
		openConnection(server, stack);
		return stack;
	}
	
	private ProtocolStack onDisconnect(InetSocketAddress source, ClientServer server, Disconnect disconnect) {
		// Sets the current stack to null if it matches the Disconnect packet
		ProtocolStack stack = stacks.get(server);
		if (stack != null && stack.getSource().equals(source) && stack.getConnectionId() == disconnect.getConnectionId()) {
			closeConnection(server);
		} else {
			send(source, server, new Disconnect(disconnect.getConnectionId(), DisconnectReason.APPLICATION).encode().array());
		}
		return stack;
	}
	
	private void openConnection(ClientServer server, ProtocolStack stack) {
		closeConnection(server);
		if (server == ClientServer.LOGIN) {
			closeConnection(ClientServer.ZONE);
			intentChain.broadcastAfter(getIntentManager(), new ClientConnectedIntent());
		}
		stacks.put(server, stack);
		
		stack.send(new SessionResponse(stack.getConnectionId(), 0, (byte) 0, (byte) 0, (byte) 0, 496));
		intentChain.broadcastAfter(getIntentManager(), new StackCreatedIntent(stack));
	}
	
	private void closeConnection(ClientServer server) {
		ProtocolStack stack = stacks.remove(server);
		if (stack == null)
			return;
		stack.send(new Disconnect(stack.getConnectionId(), DisconnectReason.APPLICATION));
		intentChain.broadcastAfter(getIntentManager(), new StackDestroyedIntent(stack));
		if (stacks.isEmpty())
			intentChain.broadcastAfter(getIntentManager(), new ClientDisconnectedIntent());
	}
	
	private static Packet parse(byte [] rawData) {
		if (rawData.length < 4)
			return null;
		
		ByteBuffer data = ByteBuffer.wrap(rawData);
		short opcode = data.getShort(0);
		switch (opcode) {
			case 0x01:	return new SessionRequest(data);
			case 0x03:	return new MultiPacket(data);
			case 0x05:	return new Disconnect(data);
			case 0x06:	return new KeepAlive(data);
			case 0x07:	return new ClientNetworkStatusUpdate(data);
			case 0x09:
			case 0x0A:
			case 0x0B:
			case 0x0C:	return new DataChannel(data);
			case 0x0D:
			case 0x0E:
			case 0x0F:
			case 0x10:	return new Fragmented(data);
			case 0x11:
			case 0x12:
			case 0x13:
			case 0x14:	return new OutOfOrder(data);
			case 0x15:
			case 0x16:
			case 0x17:
			case 0x18:	return new Acknowledge(data);
			default:
				if (rawData.length >= 6)
					return new RawSWGPacket(rawData);
				Log.w("Unknown SOE packet: %d  %s", opcode, ByteUtilities.getHexString(data.array()));
				return null;
		}
	}
	
}
