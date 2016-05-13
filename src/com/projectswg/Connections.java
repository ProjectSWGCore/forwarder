package com.projectswg;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import network.packets.swg.ErrorMessage;

import com.projectswg.control.Intent;
import com.projectswg.control.IntentManager;
import com.projectswg.control.Manager;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ClientToServerPacketIntent;
import com.projectswg.intents.ServerConnectionChangedIntent;
import com.projectswg.intents.ServerToClientPacketIntent;
import com.projectswg.networking.NetInterceptor.InterceptorProperties;
import com.projectswg.resources.ClientConnectionStatus;
import com.projectswg.resources.ServerConnectionStatus;
import com.projectswg.services.PacketRecordingService;

public class Connections extends Manager {
	
	public static final String VERSION = "0.9.6";
	
	private final ServerConnection server;
	private final ClientConnection client;
	private final PacketRecordingService recording;
	private final AtomicLong serverToClient;
	private final AtomicLong clientToServer;
	private ConnectionCallback callback;
	private InetAddress addr;
	private int port;
	
	public Connections() {
		this(InetAddress.getLoopbackAddress(), 44463, 44453, true);
	}
	
	public Connections(InetAddress remoteAddr, int remotePort, int loginPort, boolean timeout) {
		setIntentManager(new IntentManager());
		this.addr = remoteAddr;
		this.port = remotePort;
		server = new ServerConnection(remoteAddr, remotePort);
		client = new ClientConnection(loginPort, timeout);
		recording = new PacketRecordingService();
		serverToClient = new AtomicLong(0);
		clientToServer = new AtomicLong(0);
		callback = null;
		
		addChildService(server);
		addChildService(client);
		addChildService(recording);
	}
	
	@Override
	public boolean initialize() {
		getIntentManager().initialize();
		registerForIntent(ClientConnectionChangedIntent.TYPE);
		registerForIntent(ServerConnectionChangedIntent.TYPE);
		registerForIntent(ClientToServerPacketIntent.TYPE);
		registerForIntent(ServerToClientPacketIntent.TYPE);
		return super.initialize();
	}
	
	@Override
	public boolean terminate() {
		boolean term = super.terminate();
		getIntentManager().terminate();
		return term;
	}
	
	@Override
	public void onIntentReceived(Intent i) {
		switch (i.getType()) {
			case ClientConnectionChangedIntent.TYPE:
				if (i instanceof ClientConnectionChangedIntent)
					processClientStatusChanged((ClientConnectionChangedIntent) i);
				break;
			case ServerConnectionChangedIntent.TYPE:
				if (i instanceof ServerConnectionChangedIntent)
					processServerStatusChanged((ServerConnectionChangedIntent) i);
				break;
			case ServerToClientPacketIntent.TYPE:
				if (i instanceof ServerToClientPacketIntent)
					onDataServerToClient(((ServerToClientPacketIntent) i).getRawData());
				break;
			case ClientToServerPacketIntent.TYPE:
				if (i instanceof ClientToServerPacketIntent)
					onDataClientToServer(((ClientToServerPacketIntent) i).getData());
				break;
		}
	}
	
	public void setCallback(ConnectionCallback callback) {
		this.callback = callback;
	}
	
	public boolean setRemote(InetAddress addr, int port) {
		if (this.addr.equals(addr) && this.port == port)
			return false;
		server.setRemoteAddress(addr, port);
		return true;
	}
	
	public InetAddress getRemoteAddress() {
		return addr;
	}
	
	public int getRemotePort() {
		return port;
	}
	
	public int getLoginPort() {
		return client.getLoginPort();
	}
	
	public int getZonePort() {
		return client.getZonePort();
	}
	
	public long getServerToClientCount() {
		return serverToClient.get();
	}
	
	public long getClientToServerCount() {
		return clientToServer.get();
	}
	
	public InterceptorProperties getInterceptorProperties() {
		return client.getInterceptorProperties();
	}
	
	private void processServerStatusChanged(ServerConnectionChangedIntent scci) {
		if (callback != null)
			callback.onServerStatusChanged(scci.getOldStatus(), scci.getStatus());
		if (scci.getStatus() != ServerConnectionStatus.CONNECTED && scci.getStatus() != ServerConnectionStatus.CONNECTING) {
			if (scci.getStatus() != ServerConnectionStatus.DISCONNECT_INVALID_PROTOCOL)
				client.send(new ErrorMessage("Connection Update", "\n" + scci.getStatus().name().replace('_', ' '), false));
			else {
				String error = "\nInvalid protocol version!";
				error += "\nTry updating your launcher to the latest version.";
				error += "\nInstalled Version: " + VERSION;
				client.send(new ErrorMessage("Network", error, false));
			}
			client.hardReset();
		}
	}
	
	private void processClientStatusChanged(ClientConnectionChangedIntent ccci) {
		switch (ccci.getStatus()) {
			case LOGIN_CONNECTED:
				if (ccci.getOldStatus() == ClientConnectionStatus.DISCONNECTED)
					onClientConnected();
				break;
			case DISCONNECTED:
				if (ccci.getOldStatus() != ClientConnectionStatus.DISCONNECTED)
					onClientDisconnected();
				break;
			default:
				break;
		}
	}
	
	private void onClientConnected() {
		server.start();
		if (callback != null)
			callback.onClientConnected();
	}
	
	private void onClientDisconnected() {
		if (callback != null)
			callback.onClientDisconnected();
	}
	
	private void onDataServerToClient(byte [] data) {
		serverToClient.addAndGet(data.length);
		if (callback != null)
			callback.onDataServerToClient(data);
	}
	
	private void onDataClientToServer(byte [] data) {
		clientToServer.addAndGet(data.length);
		if (callback != null)
			callback.onDataClientToServer(data);
	}
	
	public interface ConnectionCallback {
		void onServerStatusChanged(ServerConnectionStatus oldStatus, ServerConnectionStatus status);
		void onClientConnected();
		void onClientDisconnected();
		void onDataServerToClient(byte [] data);
		void onDataClientToServer(byte [] data);
	}
	
}
