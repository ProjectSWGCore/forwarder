package com.projectswg.forwarder.services.client;

import com.projectswg.forwarder.Forwarder.ForwarderData;
import com.projectswg.forwarder.intents.client.ClientConnectedIntent;
import com.projectswg.forwarder.intents.client.ClientDisconnectedIntent;
import com.projectswg.forwarder.intents.client.SonyPacketInboundIntent;
import com.projectswg.forwarder.intents.client.UpdateStackIntent;
import com.projectswg.forwarder.intents.control.StartForwarderIntent;
import com.projectswg.forwarder.intents.control.StopForwarderIntent;
import com.projectswg.forwarder.resources.networking.ClientServer;
import com.projectswg.forwarder.resources.networking.data.ProtocolStack;
import com.projectswg.forwarder.resources.networking.packets.*;
import me.joshlarson.jlcommon.control.IntentChain;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.Service;
import me.joshlarson.jlcommon.log.Log;
import me.joshlarson.jlcommon.network.UDPServer;
import me.joshlarson.jlcommon.utilities.ByteUtilities;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

public class ClientServerService extends Service {
	
	private final IntentChain intentChain;
	private final AtomicReference<ProtocolStack> stack;
	
	private ForwarderData data;
	private UDPServer loginServer;
	private UDPServer zoneServer;
	
	public ClientServerService() {
		this.intentChain = new IntentChain();
		this.stack = new AtomicReference<>(null);
		this.data = null;
		this.loginServer = null;
		this.zoneServer = null;
	}
	
	@Override
	public boolean isOperational() {
		return data == null || (loginServer != null && zoneServer != null && loginServer.isRunning() && zoneServer.isRunning());
	}
	
	@Override
	public boolean stop() {
		setStack(null);
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
			
			Log.t("Binding to login server...");
			loginServer.bind(this::customizeUdpServer);
			Log.t("Binding to zone server...");
			zoneServer.bind(this::customizeUdpServer);
			
			data.setLoginPort(loginServer.getPort());
			data.setZonePort(zoneServer.getPort());
			
			Log.i("Initialized login (%d) and zone servers (%d)", loginServer.getPort(), zoneServer.getPort());
		} catch (SocketException e) {
			Log.a(e);
			if (loginServer != null)
				loginServer.close();
			if (zoneServer != null)
				zoneServer.close();
			loginServer = null;
			zoneServer = null;
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
		loginServer = null;
		zoneServer = null;
		Log.i("Closed the login and zone udp servers");
	}
	
	private void customizeUdpServer(DatagramSocket socket) {
		try {
			socket.setReuseAddress(false);
			socket.setTrafficClass(0x02 | 0x04 | 0x08 | 0x10);
			socket.setBroadcast(false);
			socket.setReceiveBufferSize(496 * 2048);
			socket.setSendBufferSize(496 * 2048);
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
	
	private void send(InetSocketAddress addr, ClientServer server, byte [] data) {
		switch (server) {
			case LOGIN:
				loginServer.send(addr, data);
				break;
			case ZONE:
				zoneServer.send(addr, data);
				break;
		}
	}
	
	private void process(InetSocketAddress source, ClientServer server, byte [] data) {
		Packet parsed = parse(data);
		if (parsed == null)
			return;
		if (parsed instanceof MultiPacket) {
			for (byte [] child : ((MultiPacket) parsed).getPackets()) {
				process(source, server, child);
			}
		} else {
			broadcast(source, server, parsed);
		}
	}
	
	private void broadcast(InetSocketAddress source, ClientServer server, Packet parsed) {
		ProtocolStack stack = this.stack.get();
		if (parsed instanceof SessionRequest) {
			if (stack != null && stack.getServer() == ClientServer.ZONE)
				return;
			stack = new ProtocolStack(source, server, (remote, data) -> send(remote, server, data));
			stack.setConnectionId(((SessionRequest) parsed).getConnectionId());
			setStack(stack);
			stack.send(new SessionResponse(((SessionRequest) parsed).getConnectionId(), 0, (byte) 0, (byte) 0, (byte) 0, 496));
		}
		if (stack != null && parsed instanceof PingPacket) {
			stack.setPingSource(source);
		} else if (stack == null || !stack.getSource().equals(source) || stack.getServer() != server) {
			Log.t("[%s]@%s DROPPED %s", source, server, parsed);
			return;
		}
		Log.t("[%s]@%s sent: %s", source, server, parsed);
		intentChain.broadcastAfter(getIntentManager(), new SonyPacketInboundIntent(parsed));
		if (parsed instanceof Disconnect) {
			Log.d("Received client disconnect with id %d and reason %s", ((Disconnect) parsed).getConnectionId(), ((Disconnect) parsed).getReason());
			setStack(null);
		}
	}
	
	private void setStack(ProtocolStack stack) {
		Log.d("Updating stack: %s", stack);
		ProtocolStack oldStack = this.stack.getAndSet(stack);
		if (oldStack != null && stack == null)
			intentChain.broadcastAfter(getIntentManager(), new ClientDisconnectedIntent());
		if (stack != null && stack.getServer() == ClientServer.LOGIN)
			intentChain.broadcastAfter(getIntentManager(), new ClientConnectedIntent());
		intentChain.broadcastAfter(getIntentManager(), new UpdateStackIntent(stack));
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
				if (rawData.length == 4)
					return new PingPacket(rawData);
				if (rawData.length >= 6)
					return new RawSWGPacket(rawData);
				Log.w("Unknown SOE packet: %d  %s", opcode, ByteUtilities.getHexString(data.array()));
				return null;
		}
	}
	
}
