package com.projectswg.intents;

import com.projectswg.control.Intent;

public class ServerToClientPacketIntent extends Intent {
	
	public static final String TYPE = "ServerToClientPacketIntent";
	
	private int crc;
	private byte [] rawData;
	
	public ServerToClientPacketIntent(int crc, byte [] rawData) {
		super(TYPE);
		setCrc(crc);
		setRawData(rawData);
	}
	
	public int getCrc() {
		return crc;
	}
	
	public byte [] getRawData() {
		return rawData;
	}
	
	public void setCrc(int crc) {
		this.crc = crc;
	}
	
	public void setRawData(byte [] rawData) {
		this.rawData = rawData;
	}
	
}
