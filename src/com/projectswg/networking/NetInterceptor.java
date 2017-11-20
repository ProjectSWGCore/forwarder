package com.projectswg.networking;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.projectswg.common.data.encodables.galaxy.Galaxy;
import com.projectswg.common.network.NetBuffer;
import com.projectswg.common.network.packets.PacketType;
import com.projectswg.common.network.packets.swg.login.LoginClientId;
import com.projectswg.common.network.packets.swg.login.LoginClusterStatus;
import com.projectswg.networking.client.ClientData;

public class NetInterceptor {
	
	private final InterceptorProperties properties;
	private final ClientData clientData;
	
	public NetInterceptor(ClientData clientData) {
		this.properties = new InterceptorProperties();
		this.clientData = clientData;
	}
	
	public InterceptorProperties getProperties() {
		return properties;
	}
	
	public byte [] interceptClient(byte [] data) {
		if (data.length < 6)
			return data;
		ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		PacketType type = PacketType.fromCrc(bb.getInt(2));
		switch (type) {
			case LOGIN_CLIENT_ID:
				return setAutoLogin(NetBuffer.wrap(bb));
			case CMD_SCENE_READY:
				clientData.setZoning(false);
				return data;
			default:
				return data;
		}
	}
	
	public byte [] interceptServer(byte [] data) {
		if (data.length < 6)
			return data;
		ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		PacketType type = PacketType.fromCrc(bb.getInt(2));
		switch (type) {
			case LOGIN_CLUSTER_STATUS:
				return getServerList(NetBuffer.wrap(bb));
			default:
				return data;
		}
	}
	
	private byte [] setAutoLogin(NetBuffer data) {
		LoginClientId id = new LoginClientId(data);
		if (!id.getUsername().equals(properties.getUsername()) || !id.getPassword().isEmpty())
			return data.array();
		id.setPassword(properties.getPassword());
		return id.encode().array();
	}
	
	private byte [] getServerList(NetBuffer data) {
		LoginClusterStatus cluster = new LoginClusterStatus();
		cluster.decode(data);
		for (Galaxy g : cluster.getGalaxies()) {
			g.setAddress("127.0.0.1");
			g.setZonePort((short) properties.getPort());
			g.setPingPort((short) properties.getPort());
		}
		return cluster.encode().array();
	}
	
	public static class InterceptorProperties {
		
		private int port;
		private String username;
		private String password;
		
		public InterceptorProperties() {
			port = 0;
			username = "";
			password = "";
		}
		
		public int getPort() {
			return port;
		}
		
		public void setPort(int port) {
			this.port = port;
		}
		
		public String getUsername() {
			return username;
		}
		
		public void setUsername(String username) {
			this.username = username;
		}
		
		public String getPassword() {
			return password;
		}
		
		public void setPassword(String password) {
			this.password = password;
		}
		
	}
	
}
