package com.projectswg.networking.server;

import java.net.InetAddress;

import com.projectswg.common.control.IntentManager;
import com.projectswg.connection.ServerConnectionStatus;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ServerConnectionChangedIntent;
import com.projectswg.intents.ServerToClientPacketIntent;
import com.projectswg.resources.ClientConnectionStatus;

public class ServerConnectionWrapper {
	
	private ServerConnectionService connection;
	private ConnectionCallback callback;
	private IntentManager intentManager;
	
	public ServerConnectionWrapper(InetAddress addr, int port) {
		connection = new ServerConnectionService(addr, port);
		intentManager = null;
	}
	
	public void setConnectionCallback(ConnectionCallback callback) {
		this.callback = callback;
	}
	
	public void connect() {
		setupConnectionIntents();
		connection.initialize();
		connection.start();
		new ClientConnectionChangedIntent(ClientConnectionStatus.DISCONNECTED, ClientConnectionStatus.LOGIN_CONNECTED).broadcast(connection.getIntentManager());
	}
	
	public void disconnect() {
		new ClientConnectionChangedIntent(ClientConnectionStatus.LOGIN_CONNECTED, ClientConnectionStatus.DISCONNECTED).broadcast(connection.getIntentManager());
		connection.stop();
		connection.terminate();
		connection.getIntentManager().terminate();
	}
	
	public void send(byte [] data) {
		connection.send(data);
	}
	
	private void setupConnectionIntents() {
		intentManager = new IntentManager(Runtime.getRuntime().availableProcessors());
		connection.setIntentManager(intentManager);
		intentManager.registerForIntent(ServerConnectionChangedIntent.class, scci -> onServerConnectionChanged(scci));
		intentManager.registerForIntent(ServerToClientPacketIntent.class, scpi -> onServerData(scpi));
	}
	
	private void onServerConnectionChanged(ServerConnectionChangedIntent i) {
		if (callback != null)
			callback.onServerConnectionChanged(i.getOldStatus(), i.getStatus());
	}
	
	private void onServerData(ServerToClientPacketIntent i) {
		if (callback != null)
			callback.onServerPacket(i.getCrc(), i.getRawData());
	}
	
	public interface ConnectionCallback {
		void onServerConnectionChanged(ServerConnectionStatus old, ServerConnectionStatus status);
		void onServerPacket(int crc, byte [] data);
	}
	
}
