package com.projectswg.forwarder.intents.client;

import com.projectswg.forwarder.resources.networking.packets.Packet;
import me.joshlarson.jlcommon.control.Intent;
import org.jetbrains.annotations.NotNull;

public class SonyPacketInboundIntent extends Intent {
	
	private final Packet packet;
	
	public SonyPacketInboundIntent(@NotNull Packet packet) {
		this.packet = packet;
	}
	
	@NotNull
	public Packet getPacket() {
		return packet;
	}
	
}
