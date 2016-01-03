package com.projectswg.networking.soe;

import java.nio.ByteBuffer;

import com.projectswg.networking.Packet;

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
	
}
