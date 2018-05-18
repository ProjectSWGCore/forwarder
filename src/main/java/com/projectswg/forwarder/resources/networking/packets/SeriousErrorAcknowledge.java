package com.projectswg.forwarder.resources.networking.packets;

import java.nio.ByteBuffer;

public class SeriousErrorAcknowledge extends Packet {
	
	public SeriousErrorAcknowledge() {
		
	}
	
	public SeriousErrorAcknowledge(ByteBuffer data) {
		decode(data);
	}
	
	public void decode(ByteBuffer data) {
		data.position(2);
	}
	
	public ByteBuffer encode() {
		ByteBuffer data = ByteBuffer.allocate(2);
		addNetShort(data, 0x1D);
		return data;
	}
	
}