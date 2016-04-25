package com.projectswg;

import java.net.BindException;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import network.packets.swg.SWGPacket;

import com.projectswg.control.Intent;
import com.projectswg.control.Service;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ClientSonyPacketIntent;
import com.projectswg.intents.ServerToClientPacketIntent;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.Packet;
import com.projectswg.networking.UDPServer;
import com.projectswg.networking.UDPServer.UDPCallback;
import com.projectswg.networking.encryption.Encryption;
import com.projectswg.networking.sender.PacketPackager;
import com.projectswg.networking.sender.PacketResender;
import com.projectswg.networking.soe.Acknowledge;
import com.projectswg.networking.soe.Disconnect;
import com.projectswg.networking.soe.OutOfOrder;
import com.projectswg.networking.soe.SequencedPacket;
import com.projectswg.networking.soe.SessionResponse;
import com.projectswg.networking.soe.Disconnect.DisconnectReason;
import com.projectswg.resources.ClientConnectionStatus;
import com.projectswg.utilities.Log;

public class ClientSender extends Service {
	
	private static final InetAddress ADDR = InetAddress.getLoopbackAddress();
	
	private final AtomicBoolean serverRunning;
	private final PacketPackager packager;
	private final PacketResender resender;
	private UDPServer loginServer;
	private UDPServer zoneServer;
	private int connectionId;
	private int port;
	private int loginPort;
	private boolean zone;
	
	public ClientSender(NetInterceptor interceptor, int loginPort) {
		this.serverRunning = new AtomicBoolean(false);
		this.packager = new PacketPackager(interceptor, (p) -> send(p));
		this.resender = new PacketResender((data) -> sendRaw(data));
		this.loginPort = loginPort;
		connectionId = -1;
		port = 0;
		zone = false;
	}
	
	public boolean initialize() {
		registerForIntent(ClientConnectionChangedIntent.TYPE);
		registerForIntent(ServerToClientPacketIntent.TYPE);
		registerForIntent(ClientSonyPacketIntent.TYPE);
		connectionId = -1;
		port = 0;
		zone = false;
		return super.initialize();
	}
	
	public boolean start() {
		reset();
		port = 0;
		zone = false;
		serverRunning.set(false);
		packager.start();
		resender.start();
		try {
			loginServer = new UDPServer(loginPort, 496);
			zoneServer = new UDPServer(0, 496);
			loginPort = loginServer.getPort();
			serverRunning.set(true);
		} catch (BindException e) {
			Log.err(this, "Failed to bind UDP servers! Login Port: " + loginPort);
			return false;
		} catch (SocketException e) {
			Log.err(this, e);
			return false;
		}
		return super.start();
	}
	
	public boolean stop() {
		packager.stop();
		resender.stop();
		reset();
		if (loginServer != null)
			loginServer.setCallback(null);
		if (zoneServer != null)
			zoneServer.setCallback(null);
		disconnect(DisconnectReason.APPLICATION);
		serverRunning.set(false);
		safeCloseServers();
		return super.stop();
	}
	
	@Override
	public void onIntentReceived(Intent i) {
		if (i instanceof ClientConnectionChangedIntent) {
			ClientConnectionStatus old = ((ClientConnectionChangedIntent) i).getOldStatus();
			ClientConnectionStatus status = ((ClientConnectionChangedIntent) i).getStatus();
			if (old == ClientConnectionStatus.LOGIN_CONNECTED && status == ClientConnectionStatus.ZONE_CONNECTED)
				reset();
			else if (status == ClientConnectionStatus.DISCONNECTED)
				reset();
		} else if (i instanceof ServerToClientPacketIntent) {
			send(((ServerToClientPacketIntent) i).getRawData());
		} else if (i instanceof ClientSonyPacketIntent) {
			Packet p = ((ClientSonyPacketIntent) i).getPacket();
			if (p instanceof Acknowledge)
				onAcknowledge(((Acknowledge) p).getSequence());
			else if (p instanceof OutOfOrder)
				onOutOfOrder(((OutOfOrder) p).getSequence());
		}
	}
	
	public void setLoginCallback(UDPCallback callback) {
		if (loginServer != null)
			loginServer.setCallback(callback);
	}
	
	public void setZoneCallback(UDPCallback callback) {
		if (zoneServer != null)
			zoneServer.setCallback(callback);
	}
	
	public void setLoginPort(int loginPort) {
		this.loginPort = loginPort;
	}
	
	public int getLoginPort() {
		return loginPort;
	}
	
	public int getZonePort() {
		if (zoneServer == null)
			return -1;
		return zoneServer.getPort();
	}
	
	public int getSequence() {
		return packager.getSequence();
	}
	
	public int getConnectionId() {
		return connectionId;
	}
	
	public boolean isRunning() {
		return serverRunning.get();
	}
	
	public void setZone(boolean zone) {
		this.zone = zone;
	}
	
	public void setPort(int port) {
		this.port = port;
	}
	
	public void setConnectionId(int connectionId) {
		this.connectionId = connectionId;
	}
	
	public void disconnect(DisconnectReason reason) {
		if (connectionId != -1)
			send(new Disconnect(connectionId, reason));
		connectionId = -1;
	}
	
	public void reset() {
		packager.reset();
		resender.reset();
		connectionId = -1;
	}
	
	public void onOutOfOrder(short sequence) {
		resender.resendTo(sequence);
	}
	
	public void onAcknowledge(short sequence) {
		resender.clearTo(sequence);
	}
	
	public void send(SWGPacket ... packets) {
		for (SWGPacket packet : packets) {
			send(packet.encode().array());
		}
	}
	
	public void send(byte [] data) {
		packager.addToPackage(data);
	}
	
	public void sendRaw(byte [] data) {
		sendRaw(port, data);
	}
	
	public void sendRaw(int port, byte [] data) {
		if (port == 0) {
			Log.err(this, "Cannot send to port 0!  Data: " + Arrays.toString(data));
			return;
		}
		if (zone && zoneServer != null)
			zoneServer.send(port, ADDR, data);
		else if (!zone && loginServer != null)
			loginServer.send(port, ADDR, data);
		else {
			Log.err(this, "No matching server! isZone=%b: Login=%b Zone=%b", zone, loginServer!=null, zoneServer!=null);
			return;
		}
	}
	
	public void send(Packet packet) {
		if (port == 0)
			return;
		byte [] data;
		if (packet instanceof SessionResponse)
			data = packet.encode().array();
		else
			data = Encryption.encode(packet.encode().array(), 0);
		if (packet instanceof SequencedPacket) {
			resender.add(((SequencedPacket) packet).getSequence(), data);
		} else {
			sendRaw(data);
		}
	}
	
	private void safeCloseServers() {
		if (loginServer != null) {
			loginServer.close();
			loginServer = null;
		}
		if (zoneServer != null) {
			zoneServer.close();
			zoneServer = null;
		}
	}
	
	public interface ClientSenderCallback {
		void onUdpSent(boolean zone, byte [] data);
	}
	
}
