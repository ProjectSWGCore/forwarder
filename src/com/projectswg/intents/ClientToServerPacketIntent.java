package com.projectswg.intents;

import com.projectswg.common.control.Intent;

public class ClientToServerPacketIntent extends Intent {
	
	private byte [] data;
	
	public ClientToServerPacketIntent(byte [] data) {
		setData(data);
	}
	
	public byte [] getData() {
		return data;
	}
	
	public void setData(byte [] data) {
		this.data = data;
	}
	
}
