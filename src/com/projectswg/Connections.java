package com.projectswg;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import network.packets.swg.ErrorMessage;

import com.projectswg.control.IntentManager;
import com.projectswg.control.Manager;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ClientToServerPacketIntent;
import com.projectswg.intents.ServerConnectionChangedIntent;
import com.projectswg.intents.ServerToClientPacketIntent;
import com.projectswg.networking.NetInterceptor.InterceptorProperties;
import com.projectswg.networking.client.ClientConnectionService;
import com.projectswg.networking.server.ServerConnectionService;
import com.projectswg.networking.server.ServerConnectionChangedReason;
import com.projectswg.networking.soe.Disconnect.DisconnectReason;
import com.projectswg.resources.ServerConnectionStatus;
import com.projectswg.services.PacketRecordingService;
import com.projectswg.utilities.Log;
import com.projectswg.utilities.ThreadUtilities;

public class Connections extends Manager {
	
	public static final String VERSION = "0.9.8";
	
	private final ServerConnectionService server;
	private final ClientConnectionService client;
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
		this.server = new ServerConnectionService(remoteAddr, remotePort);
		this.client = new ClientConnectionService(loginPort, timeout);
		this.recording = new PacketRecordingService();
		this.serverToClient = new AtomicLong(0);
		this.clientToServer = new AtomicLong(0);
		this.callback = null;
		this.addr = null;
		this.port = 0;
		setRemote(remoteAddr, remotePort);
		
		addChildService(server);
		addChildService(client);
		addChildService(recording);
	}
	
	@Override
	public boolean initialize() {
		getIntentManager().initialize();
		registerForIntent(ClientConnectionChangedIntent.class, ccci -> processClientStatusChanged(ccci));
		registerForIntent(ServerConnectionChangedIntent.class, scci -> processServerStatusChanged(scci));
		registerForIntent(ClientToServerPacketIntent.class, ctspi -> onDataClientToServer(ctspi.getData()));
		registerForIntent(ServerToClientPacketIntent.class, stcpi -> onDataServerToClient(stcpi.getRawData()));
		return super.initialize();
	}
	
	@Override
	public boolean terminate() {
		getIntentManager().terminate();
		return super.terminate();
	}
	
	public void setCallback(ConnectionCallback callback) {
		this.callback = callback;
	}
	
	public boolean setRemote(InetAddress addr, int port) {
		this.addr = addr;
		this.port = port;
		recording.setAddress(new InetSocketAddress("::1", 0), server.getRemoteAddress());
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
		if (scci.getStatus() == ServerConnectionStatus.DISCONNECTED && scci.getReason() != ServerConnectionChangedReason.CLIENT_DISCONNECT) {
			Log.out(this, "Shutting down client due to server status: %s and reason %s", scci.getStatus(), scci.getReason());
			String title = "";
			String text = "";
			if (scci.getReason() != ServerConnectionChangedReason.INVALID_PROTOCOL) {
				title = "Connection Lost";
				text = "\n" + scci.getReason().name().replace('_', ' ');
			} else {
				title = "Network";
				text  = "\nInvalid protocol version!";
				text += "\nTry updating your launcher to the latest version.";
				text += "\nInstalled Version: " + VERSION;
			}
			client.sendPackaged(new ErrorMessage(title, text, false));
			client.waitForClientAcknowledge();
			ThreadUtilities.sleep(100);
			client.disconnect(DisconnectReason.OTHER_SIDE_TERMINATED);
			client.restart();
		}
	}
	
	private void processClientStatusChanged(ClientConnectionChangedIntent ccci) {
		switch (ccci.getStatus()) {
			case LOGIN_CONNECTED:
				onClientConnected();
				break;
			case DISCONNECTED:
				onClientDisconnected();
				break;
			default:
				break;
		}
	}
	
	private void onClientConnected() {
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
