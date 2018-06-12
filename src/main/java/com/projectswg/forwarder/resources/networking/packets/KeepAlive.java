package com.projectswg.forwarder.resources.networking.packets;

import java.nio.ByteBuffer;

public class KeepAlive extends Packet {
	
	public KeepAlive() {
		
	}
	
	public KeepAlive(ByteBuffer data) {
		decode(data);
	}
	
	public void decode(ByteBuffer data) {
		data.position(2);
	}
	
	public ByteBuffer encode() {
		ByteBuffer data = ByteBuffer.allocate(2);
		addNetShort(data, 0x06);
		return data;
	}
	
	@Override
	public String toString() {
		return String.format("KeepAlive[]");
	}
	
}
