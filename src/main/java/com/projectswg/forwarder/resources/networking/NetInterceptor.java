package com.projectswg.forwarder.resources.networking;

import com.projectswg.common.data.encodables.galaxy.Galaxy;
import com.projectswg.common.network.NetBuffer;
import com.projectswg.common.network.packets.PacketType;
import com.projectswg.common.network.packets.swg.login.LoginClientId;
import com.projectswg.common.network.packets.swg.login.LoginClusterStatus;
import com.projectswg.forwarder.Forwarder.ForwarderData;
import me.joshlarson.jlcommon.log.Log;
import me.joshlarson.jlcommon.utilities.ByteUtilities;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class NetInterceptor {
	
	private final ForwarderData data;
	
	public NetInterceptor(ForwarderData data) {
		this.data = data;
	}
	
	public byte[] interceptClient(byte[] data) {
		if (data.length < 6)
			return data;
		ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		PacketType type = PacketType.fromCrc(bb.getInt(2));
		switch (type) {
			case LOGIN_CLIENT_ID:
				return setAutoLogin(NetBuffer.wrap(bb));
			default:
				return data;
		}
	}
	
	public byte[] interceptServer(byte[] data) {
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
	
	private byte[] setAutoLogin(NetBuffer data) {
		LoginClientId id = new LoginClientId(data);
		if (!id.getUsername().equals(this.data.getUsername()) || !id.getPassword().isEmpty())
			return data.array();
		id.setPassword(this.data.getPassword());
		return id.encode().array();
	}
	
	private byte[] getServerList(NetBuffer data) {
		LoginClusterStatus cluster = new LoginClusterStatus();
		cluster.decode(data);
		for (Galaxy g : cluster.getGalaxies()) {
			g.setAddress("127.0.0.1");
			g.setZonePort(this.data.getZonePort());
			g.setPingPort(this.data.getZonePort());
		}
		return cluster.encode().array();
	}
	
}
