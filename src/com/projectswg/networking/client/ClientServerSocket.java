package com.projectswg.networking.client;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectswg.control.Assert;
import com.projectswg.networking.UDPServer;
import com.projectswg.utilities.Log;

/**
 * This class is in charge of the login and zone UDP servers, and switching between the two
 */
public class ClientServerSocket {
	
	private static final InetAddress ADDR = InetAddress.getLoopbackAddress();
	
	private final AtomicBoolean connected;
	private final ClientData data;
	private UDPServer loginServer;
	private UDPServer zoneServer;
	private int loginPort;
	
	public ClientServerSocket(ClientData data, int loginPort) {
		Assert.test(loginPort >= 0, "Login port must be positive!");
		this.connected = new AtomicBoolean(false);
		this.data = data;
		this.loginServer = null;
		this.zoneServer = null;
		setLoginPort(loginPort);
	}
	
	public boolean connect(SocketCallback callback) {
		Assert.test(!connected.getAndSet(true));
		Assert.notNull(callback);
		try {
			loginServer = new UDPServer(loginPort, 496);
			zoneServer = new UDPServer(0, 496);
			loginServer.bind();
			zoneServer.bind();
			loginPort = loginServer.getPort();
			Assert.test(loginPort > 0, "Login port was not set correctly by the UDPServer!");
			loginServer.setCallback((packet) -> callback.onPacket(new IncomingPacket(ClientServer.LOGIN, packet)));
			zoneServer.setCallback((packet) -> callback.onPacket(new IncomingPacket(ClientServer.ZONE, packet)));
			return true;
		} catch (SocketException e) {
			disconnect();
			Log.err(this, e);
		}
		return false;
	}
	
	public void disconnect() {
		Assert.test(connected.getAndSet(false));
		Assert.notNull(loginServer);
		Assert.notNull(zoneServer);
		loginServer.removeCallback();
		loginServer.close();
		zoneServer.removeCallback();
		zoneServer.close();
	}
	
	public void send(byte [] packet) {
		Assert.test(packet.length > 0, "Packet length cannot be 0!");
		Assert.test(data.getCommunicationPort() > 0, "Communication port has not been set!");
		getServer().send(data.getCommunicationPort(), ADDR, packet);
	}
	
	public void send(DatagramPacket packet) {
		Assert.test(packet.getLength() > 0, "Packet length cannot be 0!");
		Assert.test(packet.getData().length == packet.getLength(), "Data length and packet length do not match!");
		getServer().send(packet);
	}
	
	private UDPServer getServer() {
		switch (data.getClientServer()) {
			case LOGIN:
				return loginServer;
			case ZONE:
				return zoneServer;
			default:
				Assert.fail("Unknown server: " + data.getClientServer());
				return null;
		}
	}
	
	public void setLoginPort(int loginPort) {
		Assert.test(loginPort >= 0, "Login port must be >= 0");
		this.loginPort = loginPort;
	}
	
	public int getLoginPort() {
		Assert.notNull(loginServer, "Login server has not been initialized");
		return loginServer.getPort();
	}
	
	public int getZonePort() {
		Assert.notNull(zoneServer, "Zone server has not been initialized");
		return zoneServer.getPort();
	}
	
	public boolean isConnected() {
		return connected.get();
	}
	
	public enum ClientServer {
		LOGIN,
		ZONE
	}
	
	public interface SocketCallback {
		void onPacket(IncomingPacket incoming);
	}
	
	public static class IncomingPacket {
		private final ClientServer server;
		private final DatagramPacket packet;
		
		public IncomingPacket(ClientServer server, DatagramPacket packet) {
			Assert.notNull(server, "Server cannot be null");
			Assert.notNull(packet, "Packet cannot be null");
			this.server = server;
			this.packet = packet;
		}
		
		public ClientServer getServer() {
			return server;
		}
		
		public DatagramPacket getPacket() {
			return packet;
		}
		
		public int getPort() {
			return packet.getPort();
		}
		
		public byte [] getData() {
			return packet.getData();
		}
		
		public int getLength() {
			return packet.getData().length;
		}
	}
	
}
