package com.projectswg.forwarder.intents.client;

import com.projectswg.forwarder.resources.networking.packets.Packet;
import me.joshlarson.jlcommon.control.Intent;

import javax.annotation.Nonnull;

public class SonyPacketInboundIntent extends Intent {
	
	private final Packet packet;
	
	public SonyPacketInboundIntent(@Nonnull Packet packet) {
		this.packet = packet;
	}
	
	@Nonnull
	public Packet getPacket() {
		return packet;
	}
	
}
