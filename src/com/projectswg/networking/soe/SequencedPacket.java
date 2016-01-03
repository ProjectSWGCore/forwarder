package com.projectswg.networking.soe;

public interface SequencedPacket extends Comparable<SequencedPacket> {
	short getSequence();
}
