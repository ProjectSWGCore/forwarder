package com.projectswg.intents;

import com.projectswg.control.Intent;

public class ClientToServerPacketIntent extends Intent {
	
	public static final String TYPE = "ClientToServerPacketIntent";
	
	private byte [] data;
	
	public ClientToServerPacketIntent(byte [] data) {
		super(TYPE);
		setData(data);
	}
	
	public byte [] getData() {
		return data;
	}
	
	public void setData(byte [] data) {
		this.data = data;
	}
	
}
