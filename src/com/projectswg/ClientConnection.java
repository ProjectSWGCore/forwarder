package com.projectswg;

import com.projectswg.ClientReceiver.ClientReceiverCallback;
import com.projectswg.ClientReceiver.ConnectionState;
import com.projectswg.networking.NetInterceptor;

public class ClientConnection {
	
	private ClientSender sender;
	private ClientReceiver receiver;
	private ClientCallback callback;
	private NetInterceptor interceptor;
	
	public ClientConnection(int loginPort, int zonePort) {
		this.callback = null;
		interceptor = new NetInterceptor();
		sender = new ClientSender(interceptor, loginPort, zonePort);
		receiver = new ClientReceiver(interceptor);
		receiver.setClientSender(sender);
	}
	
	public void start() {
		sender.start();
		receiver.start();
		interceptor.setPort(sender.getZonePort());
		sender.setLoginCallback((packet) -> receiver.onPacket(false, packet));
		sender.setZoneCallback((packet) -> receiver.onPacket(true, packet));
		receiver.setReceiverCallback(new ClientReceiverCallback() {
			public void onPacket(byte[] data) { ClientConnection.this.onPacket(data); }
			public void onDisconnected() { ClientConnection.this.onDisconnected(); }
			public void onConnected() { ClientConnection.this.onConnected(); }
			public void onConnectionChanged(ConnectionState state) { ClientConnection.this.onConnectionStateChanged(state); }
		});
	}
	
	public void stop() {
		sender.stop();
		receiver.stop();
	}
	
	public void setCallback(ClientCallback callback) {
		this.callback = callback;
	}
	
	public void send(byte [] data) {
		sender.send(data);
	}
	
	private void onPacket(byte [] data) {
		if (callback != null)
			callback.onPacket(data);
	}
	
	private void onDisconnected() {
		if (callback != null)
			callback.onDisconnected();
		sender.reset();
		receiver.reset();
	}
	
	private void onConnected() {
		if (callback != null)
			callback.onConnected();
		sender.reset();
		receiver.reset();
	}
	
	private void onConnectionStateChanged(ConnectionState state) {
		switch (state) {
			case DISCONNECTED:
			case LOGIN_CONNECTED:
			case ZONE_CONNECTED:
				sender.reset();
				receiver.reset();
				break;
			default:
				break;
		}
	}
	
	public interface ClientCallback {
		void onConnected();
		void onDisconnected();
		void onPacket(byte [] data);
	}
	
}
