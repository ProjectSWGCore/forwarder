package com.projectswg.forwarder.intents.client;

import com.projectswg.forwarder.resources.networking.data.ProtocolStack;
import com.projectswg.forwarder.resources.networking.packets.Packet;
import me.joshlarson.jlcommon.control.Intent;
import org.jetbrains.annotations.NotNull;

public class SonyPacketInboundIntent extends Intent {
	
	private final ProtocolStack stack;
	private final Packet packet;
	
	public SonyPacketInboundIntent(@NotNull ProtocolStack stack, @NotNull Packet packet) {
		this.stack = stack;
		this.packet = packet;
	}
	
	@NotNull
	public ProtocolStack getStack() {
		return stack;
	}
	
	@NotNull
	public Packet getPacket() {
		return packet;
	}
	
}
