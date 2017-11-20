package com.projectswg.networking.client.sender;

import com.projectswg.networking.soe.SequencedPacket;

public class SequencedOutbound implements SequencedPacket {
	
	private final int sequence;
	private final byte [] data;
	
	public SequencedOutbound(short sequence, byte [] data) {
		this.sequence = sequence & 0xFFFF;
		this.data = data;
	}
	
	@Override
	public short getSequence() { return (short) sequence; }
	public int getSequenceInt() { return sequence; }
	public byte [] getData() { return data; }
	
	@Override
	public int compareTo(SequencedPacket p) {
		if (getSequence() < p.getSequence())
			return -1;
		if (getSequence() == p.getSequence())
			return 0;
		return 1;
	}
	
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof SequencedOutbound))
			return super.equals(o);
		return ((SequencedOutbound) o).getSequence() == sequence;
	}
	
	@Override
	public int hashCode() {
		return sequence;
	}
}
