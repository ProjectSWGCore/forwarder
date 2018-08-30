package com.projectswg.forwarder.resources.networking.packets;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

public interface SequencedPacket extends Comparable<SequencedPacket> {
	void setSequence(short sequence);
	short getSequence();
	
	ByteBuffer encode();
	
	default int compareTo(@NotNull SequencedPacket p) {
		return compare(this, p);
	}
	
	static int compare(@NotNull SequencedPacket a, @NotNull SequencedPacket b) {
		return compare(a.getSequence(), b.getSequence());
	}
	
	static int compare(short a, short b) {
		int aSeq = a & 0xFFFF;
		int bSeq = b & 0xFFFF;
		if (aSeq == bSeq)
			return 0;
		
		int diff = bSeq - aSeq;
		if (diff <= 0)
			diff += 0x10000;
		
		return diff < 30000 ? -1 : 1;
	}
	
}
