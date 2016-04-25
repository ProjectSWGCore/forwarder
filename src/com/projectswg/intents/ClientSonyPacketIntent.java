package com.projectswg.intents;

import com.projectswg.control.Intent;
import com.projectswg.networking.Packet;

public class ClientSonyPacketIntent extends Intent {
	
	public static final String TYPE = "ClientSonyPacketIntent";
	
	private Packet packet;
	
	public ClientSonyPacketIntent() {
		this(null);
	}
	
	public ClientSonyPacketIntent(Packet packet) {
		super(TYPE);
		setPacket(packet);
	}
	
	public Packet getPacket() {
		return packet;
	}
	
	public void setPacket(Packet packet) {
		this.packet = packet;
	}
	
}
