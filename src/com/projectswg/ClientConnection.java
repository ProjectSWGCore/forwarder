package com.projectswg;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.projectswg.ClientReceiver.ClientReceiverCallback;
import com.projectswg.ClientReceiver.ConnectionState;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.Packet;
import com.projectswg.networking.NetInterceptor.InterceptorProperties;
import com.projectswg.networking.swg.HeartBeat;

public class ClientConnection {
	
	private ScheduledExecutorService pinger;
	private ClientSender sender;
	private ClientReceiver receiver;
	private ClientCallback callback;
	private NetInterceptor interceptor;
	private boolean connected;
	
	public ClientConnection(int loginPort) {
		callback = null;
		connected = false;
		interceptor = new NetInterceptor();
		sender = new ClientSender(interceptor, loginPort);
		receiver = new ClientReceiver(interceptor);
		receiver.setClientSender(sender);
	}
	
	public InterceptorProperties getInterceptorProperties() {
		return interceptor.getProperties();
	}
	
	public boolean restart() {
		stop();
		return start();
	}
	
	public boolean start() {
		if (!sender.start())
			return false;
		receiver.start();
		interceptor.getProperties().setPort(sender.getZonePort());
		sender.setLoginCallback((packet) -> receiver.onPacket(false, packet));
		sender.setZoneCallback((packet) -> receiver.onPacket(true, packet));
		receiver.setReceiverCallback(new ClientReceiverCallback() {
			public void onPacket(byte[] data) { ClientConnection.this.onPacket(data); }
			public void onUdpRecv(boolean zone, byte[] data) { ClientConnection.this.onUdpRecv(zone, data); }
			public void onDisconnected() { ClientConnection.this.onDisconnected(); }
			public void onConnected() { ClientConnection.this.onConnected(); }
			public void onConnectionChanged(ConnectionState state) { ClientConnection.this.onConnectionStateChanged(state); }
		});
		sender.setSenderCallback((zone, data) -> onUdpSent(zone, data));
		pinger = Executors.newSingleThreadScheduledExecutor();
		pinger.scheduleAtFixedRate(()->ping(), 0, 1000, TimeUnit.MILLISECONDS);
		return true;
	}
	
	private void stop() {
		if (pinger != null)
			pinger.shutdownNow();
		sender.stop();
		receiver.stop();
		onDisconnected();
	}
	
	public void setCallback(ClientCallback callback) {
		this.callback = callback;
	}
	
	public void setLoginPort(int loginPort) {
		sender.setLoginPort(loginPort);
	}
	
	public void send(byte [] data) {
		sender.send(data);
	}
	
	public void send(Packet packet) {
		sender.send(packet);
	}
	
	public int getLoginPort() {
		return sender.getLoginPort();
	}
	
	public int getZonePort() {
		return sender.getZonePort();
	}
	
	private void onPacket(byte [] data) {
		if (callback != null)
			callback.onPacket(data);
	}
	
	private void onUdpSent(boolean zone, byte [] data) {
		if (callback != null)
			callback.onUdpSent(zone, data);
	}
	
	private void onUdpRecv(boolean zone, byte [] data) {
		if (callback != null)
			callback.onUdpRecv(zone, data);
	}
	
	private void onDisconnected() {
		boolean prev = connected;
		connected = false;
		if (callback != null && prev)
			callback.onDisconnected();
		sender.reset();
		receiver.reset();
	}
	
	private void onConnected() {
		boolean prev = connected;
		connected = true;
		if (callback != null && !prev)
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
		if (receiver.getTimeSinceLastPacket() > 5000 && !interceptor.getData().isZoning())
			onDisconnected();
		else if (receiver.getTimeSinceLastPacket() > 15000 && interceptor.getData().isZoning())
			onDisconnected();
		else
			sender.send(new HeartBeat().encode().array());
	}
	
	public interface ClientCallback {
		void onConnected();
		void onDisconnected();
		void onPacket(byte [] data);
		void onUdpSent(boolean zone, byte [] data);
		void onUdpRecv(boolean zone, byte [] data);
	}
	
}
