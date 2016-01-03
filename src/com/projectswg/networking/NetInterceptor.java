package com.projectswg.networking;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.projectswg.networking.resources.Galaxy;
import com.projectswg.networking.swg.LoginClusterStatus;

public class NetInterceptor {
	
	private int port;
	
	public NetInterceptor() {
		
	}
	
	public void setPort(int port) {
		this.port = port;
	}
	
	public byte [] interceptClient(byte [] data) {
		return data;
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
	
	private byte [] getServerList(ByteBuffer data) {
		LoginClusterStatus cluster = new LoginClusterStatus();
		cluster.decode(data);
		for (Galaxy g : cluster.getGalaxies()) {
			g.setAddress("127.0.0.1");
			g.setZonePort((short) port);
			g.setPingPort((short) port);
		}
		return cluster.encode().array();
	}
	
}
