package com.projectswg.intents;

import network.PacketType;

import com.projectswg.control.Intent;

public class ServerToClientPacketIntent extends Intent {
	
	public static final String TYPE = "ServerToClientPacketIntent";
	
	private PacketType type;
	private byte [] rawData;
	
	public ServerToClientPacketIntent(PacketType type, byte [] rawData) {
		super(TYPE);
		setPacketType(type);
		setRawData(rawData);
	}
	
	public PacketType getPacketType() {
		return type;
	}
	
	public byte [] getRawData() {
		return rawData;
	}
	
	public void setPacketType(PacketType type) {
		this.type = type;
	}
	
	public void setRawData(byte [] rawData) {
		this.rawData = rawData;
	}
	
}
