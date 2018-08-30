package com.projectswg.forwarder.resources.networking.data;

import com.projectswg.forwarder.resources.networking.packets.SequencedPacket;

import java.nio.ByteBuffer;

public class SequencedOutbound implements SequencedPacket {
	
	private final SequencedPacket packet;
	private byte [] data;
	private boolean sent;
	
	public SequencedOutbound(SequencedPacket packet) {
		this.packet = packet;
		this.data = packet.encode().array();
		this.sent = false;
	}
	
	@Override
	public short getSequence() {
		return packet.getSequence();
	}
	
	public byte[] getData() {
		return data;
	}
	
	public boolean isSent() {
		return sent;
	}
	
	@Override
	public ByteBuffer encode() {
		return packet.encode();
	}
	
	@Override
	public void setSequence(short sequence) {
		this.packet.setSequence(sequence);
		this.data = packet.encode().array();
	}
	
	public void setSent(boolean sent) {
		this.sent = sent;
	}
	
}
