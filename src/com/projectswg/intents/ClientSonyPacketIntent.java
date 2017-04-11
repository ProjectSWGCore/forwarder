package com.projectswg.intents;

import com.projectswg.common.control.Intent;
import com.projectswg.networking.Packet;

public class ClientSonyPacketIntent extends Intent {
	
	private Packet packet;
	
	public ClientSonyPacketIntent() {
		this(null);
	}
	
	public ClientSonyPacketIntent(Packet packet) {
		setPacket(packet);
	}
	
	public Packet getPacket() {
		return packet;
	}
	
	public void setPacket(Packet packet) {
		this.packet = packet;
	}
	
}
