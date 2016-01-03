package com.projectswg;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.projectswg.ClientReceiver.ClientReceiverCallback;
import com.projectswg.ClientReceiver.ConnectionState;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.swg.HeartBeat;

public class ClientConnection {
	
	private ScheduledExecutorService pinger;
	private ClientSender sender;
	private ClientReceiver receiver;
	private ClientCallback callback;
	private NetInterceptor interceptor;
	private boolean connected;
	
	public ClientConnection(int loginPort, int zonePort) {
		callback = null;
		connected = false;
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
		pinger = Executors.newSingleThreadScheduledExecutor();
		pinger.scheduleAtFixedRate(()->ping(), 0, 1000, TimeUnit.MILLISECONDS);
	}
	
	public void stop() {
		pinger.shutdownNow();
		sender.stop();
		receiver.stop();
		if (callback != null)
			callback.onDisconnected();
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
		connected = false;
		if (callback != null)
			callback.onDisconnected();
		sender.reset();
		receiver.reset();
	}
	
	private void onConnected() {
		connected = true;
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
	
	private void ping() {
		if (!connected)
			return;
		if (receiver.getTimeSinceLastPacket() > 5000)
			onDisconnected();
		else
			sender.send(new HeartBeat().encode().array());
	}
	
	public interface ClientCallback {
		void onConnected();
		void onDisconnected();
		void onPacket(byte [] data);
	}
	
}
