package com.projectswg;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

import com.projectswg.ClientConnection.ClientCallback;
import com.projectswg.ServerConnection.ServerCallback;

public class Connections {
	
	private final ServerConnection server;
	private final ClientConnection client;
	private final AtomicLong tcpRecv;
	private final AtomicLong tcpSent;
	private final AtomicLong udpRecv;
	private final AtomicLong udpSent;
	private ConnectionCallback callback;
	private InetAddress addr;
	private int port;
	private int loginPort;
	
	public Connections() {
		this(InetAddress.getLoopbackAddress(), 44463, 44453);
	}
	
	public Connections(InetAddress remoteAddr, int remotePort, int loginPort) {
		this.addr = remoteAddr;
		this.port = remotePort;
		this.loginPort = loginPort;
		server = new ServerConnection(remoteAddr, remotePort);
		client = new ClientConnection(loginPort);
		tcpRecv = new AtomicLong(0);
		tcpSent = new AtomicLong(0);
		udpRecv = new AtomicLong(0);
		udpSent = new AtomicLong(0);
		callback = null;
		setCallbacks();
	}
	
	public void initialize() {
		int attempts = 0;
		while (!client.start() && attempts < 5) {
			loginPort++;
			client.setLoginPort(loginPort);
			attempts++;
		}
	}
	
	public void terminate() {
		server.stop();
		client.stop();
	}
	
	public void setCallback(ConnectionCallback callback) {
		this.callback = callback;
	}
	
	public void setRemote(InetAddress addr, int port) {
		if (this.addr.equals(addr) && this.port == port)
			return;
		terminate();
		server.setRemoteAddress(addr, port);
		initialize();
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
	
	public long getTcpRecv() {
		return tcpRecv.get();
	}
	
	public long getTcpSent() {
		return tcpSent.get();
	}
	
	public long getUdpRecv() {
		return udpRecv.get();
	}
	
	public long getUdpSent() {
		return udpSent.get();
	}
	
	private void setCallbacks() {
		client.setCallback(new ClientCallback() {
			public void onPacket(byte[] data) { onDataSentTcp(data); }
			public void onUdpSent(boolean zone, byte[] data) { onDataSentUdp(data); }
			public void onUdpRecv(boolean zone, byte[] data) { onDataRecvUdp(data); }
			public void onDisconnected() { onClientDisconnected(); }
			public void onConnected() { onClientConnected(); }
		});
		server.setCallback(new ServerCallback() {
			public void onData(byte[] data) { onDataRecvTcp(data); }
			public void onConnected() { onServerConnected(); }
			public void onDisconnected() { onServerDisconnected(); }
		});
	}
	
	private void onServerConnected() {
		if (callback != null)
			callback.onServerConnected();
	}
	
	private void onServerDisconnected() {
		if (callback != null)
			callback.onServerDisconnected();
	}
	
	private void onClientConnected() {
		server.start();
		if (callback != null)
			callback.onClientConnected();
	}
	
	private void onClientDisconnected() {
		server.stop();
		if (callback != null)
			callback.onClientDisconnected();
	}
	
	private void onDataRecvTcp(byte [] data) {
		tcpRecv.addAndGet(data.length);
		client.send(data);
		if (callback != null)
			callback.onDataRecvTcp(data);
	}
	
	private void onDataSentTcp(byte [] data) {
		tcpSent.addAndGet(data.length);
		server.send(data);
		if (callback != null)
			callback.onDataSentTcp(data);
	}
	
	private void onDataRecvUdp(byte [] data) {
		udpRecv.addAndGet(data.length);
		if (callback != null)
			callback.onDataRecvUdp(data);
	}
	
	private void onDataSentUdp(byte [] data) {
		udpSent.addAndGet(data.length);
		if (callback != null)
			callback.onDataSentUdp(data);
	}
	
	public interface ConnectionCallback {
		void onServerConnected();
		void onServerDisconnected();
		void onClientConnected();
		void onClientDisconnected();
		void onDataRecvTcp(byte [] data);
		void onDataSentTcp(byte [] data);
		void onDataRecvUdp(byte [] data);
		void onDataSentUdp(byte [] data);
	}
	
}
