package com.projectswg.forwarder.resources.networking.packets;

import com.projectswg.common.utilities.ByteUtilities;

import java.nio.ByteBuffer;

public class PingPacket extends Packet {
	
	private byte [] payload;
	
	public PingPacket(byte [] payload) {
		this.payload = payload;
	}
	
	@Override
	public void decode(ByteBuffer data) {
		this.payload = data.array();
	}
	
	@Override
	public ByteBuffer encode() {
		return ByteBuffer.wrap(payload);
	}
	
	public byte [] getPayload() {
		return payload;
	}
	
	@Override
	public String toString() {
		return "PingPacket[payload=" + ByteUtilities.getHexString(payload) + "]";
	}
	
}
