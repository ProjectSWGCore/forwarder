package com.projectswg.intents;

import network.packets.swg.SWGPacket;

import com.projectswg.control.Intent;

public class ServerToClientPacketIntent extends Intent {
	
	public static final String TYPE = "ServerToClientPacketIntent";
	
	private SWGPacket packet;
	private byte [] rawData;
	
	public ServerToClientPacketIntent(SWGPacket packet, byte [] rawData) {
		super(TYPE);
		setPacket(packet);
		setRawData(rawData);
	}
	
	public SWGPacket getPacket() {
		return packet;
	}
	
	public byte [] getRawData() {
		return rawData;
	}
	
	public void setPacket(SWGPacket packet) {
		this.packet = packet;
	}
	
	public void setRawData(byte [] rawData) {
		this.rawData = rawData;
	}
	
}
