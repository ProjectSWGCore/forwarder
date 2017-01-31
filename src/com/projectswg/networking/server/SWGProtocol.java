package com.projectswg.networking.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import network.PacketType;

public class SWGProtocol {
	
	private final HolocoreProtocol holocore;
	
	public SWGProtocol() {
		holocore = new HolocoreProtocol();
	}
	
	public void reset() {
		holocore.reset();
	}
	
	public ByteBuffer assemble(byte [] packet) {
		return holocore.assemble(packet);
	}
	
	public boolean addToBuffer(ByteBuffer network) {
		return holocore.addToBuffer(network);
	}
	
	public RawPacket disassemble() {
		byte [] packet = holocore.disassemble();
		if (packet.length < 6)
			return null;
		ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
		PacketType type = PacketType.fromCrc(data.getInt(2));
		return new RawPacket(type, packet);
	}
	
	public static class RawPacket {
		
		private final PacketType type;
		private final byte [] data;
		
		public RawPacket(PacketType type, byte [] data) {
			this.type = type;
			this.data = data;
		}
		
		public PacketType getPacketType() {
			return type;
		}
		
		public byte [] getData() {
			return data;
		}
		
	}
	
}
