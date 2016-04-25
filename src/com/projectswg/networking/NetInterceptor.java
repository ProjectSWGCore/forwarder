package com.projectswg.networking;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import resources.Galaxy;
import network.packets.swg.login.LoginClientId;
import network.packets.swg.login.LoginClusterStatus;

public class NetInterceptor {
	
	private final InterceptorProperties properties;
	private final InterceptorData data;
	
	public NetInterceptor() {
		properties = new InterceptorProperties();
		data = new InterceptorData();
	}
	
	public InterceptorProperties getProperties() {
		return properties;
	}
	
	public InterceptorData getData() {
		return data;
	}
	
	public byte [] interceptClient(byte [] data) {
		if (data.length < 6)
			return data;
		ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		switch (bb.getInt(2)) {
			case 0x41131F96:
				return setAutoLogin(bb);
			case 0x43FD1C22: // CmdSceneReady
				this.data.setZoning(false);
				return data;
			default:
				return data;
		}
	}
	
	public byte [] interceptServer(byte [] data) {
		if (data.length < 6)
			return data;
		ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		switch (bb.getInt(2)) {
			case 0x3436AEB6:
				return getServerList(bb);
			default:
				return data;
		}
	}
	
	private byte [] setAutoLogin(ByteBuffer data) {
		LoginClientId id = new LoginClientId(data);
		if (!id.getUsername().equals(properties.getUsername()) || !id.getPassword().isEmpty())
			return data.array();
		id.setPassword(properties.getPassword());
		return id.encode().array();
	}
	
	private byte [] getServerList(ByteBuffer data) {
		LoginClusterStatus cluster = new LoginClusterStatus();
		cluster.decode(data);
		for (Galaxy g : cluster.getGalaxies()) {
			g.setAddress("127.0.0.1");
			g.setZonePort((short) properties.getPort());
			g.setPingPort((short) properties.getPort());
		}
		return cluster.encode().array();
	}
	
	public static class InterceptorData {
		
		private boolean zoning;
		
		public InterceptorData() {
			zoning = false;
		}
		
		public boolean isZoning() {
			return zoning;
		}
		
		public void setZoning(boolean zoning) {
			this.zoning = zoning;
		}
		
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
