package com.projectswg;

import java.net.InetAddress;

import network.packets.swg.SWGPacket;

import com.projectswg.control.IntentManager;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ServerConnectionChangedIntent;
import com.projectswg.intents.ServerToClientPacketIntent;
import com.projectswg.resources.ClientConnectionStatus;
import com.projectswg.resources.ServerConnectionStatus;

public class ServerConnectionWrapper {
	
	private ServerConnection connection;
	private ConnectionCallback callback;
	private IntentManager intentManager;
	
	public ServerConnectionWrapper(InetAddress addr, int port) {
		connection = new ServerConnection(addr, port);
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
		intentManager = new IntentManager();
		connection.setIntentManager(intentManager);
		intentManager.registerForIntent(ServerConnectionChangedIntent.TYPE, (i) -> onServerConnectionChanged((ServerConnectionChangedIntent) i));
		intentManager.registerForIntent(ServerToClientPacketIntent.TYPE, (i) -> onServerData((ServerToClientPacketIntent) i));
	}
	
	private void onServerConnectionChanged(ServerConnectionChangedIntent i) {
		if (callback != null)
			callback.onServerConnectionChanged(i.getOldStatus(), i.getStatus());
	}
	
	private void onServerData(ServerToClientPacketIntent i) {
		if (callback != null)
			callback.onServerPacket(i.getPacket(), i.getRawData());
	}
	
	public interface ConnectionCallback {
		void onServerConnectionChanged(ServerConnectionStatus old, ServerConnectionStatus status);
		void onServerPacket(SWGPacket packet, byte [] data);
	}
	
}
