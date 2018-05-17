package com.projectswg.forwarder.resources.networking.data;

public class SequencedOutbound {
	
	private final short sequence;
	private final byte [] data;
	private boolean sent;
	
	public SequencedOutbound(short sequence, byte [] data) {
		this.sequence = sequence;
		this.data = data;
		this.sent = false;
	}
	
	public short getSequence() {
		return sequence;
	}
	
	public byte[] getData() {
		return data;
	}
	
	public boolean isSent() {
		return sent;
	}
	
	public void setSent(boolean sent) {
		this.sent = sent;
	}
	
}
