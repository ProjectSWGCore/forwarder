package com.projectswg.forwarder.resources.networking.packets;

public interface SequencedPacket extends Comparable<SequencedPacket> {
	short getSequence();
}
