package com.projectswg.forwarder.intents.client;

import me.joshlarson.jlcommon.control.Intent;

public class SendPongIntent extends Intent {
	
	private final byte [] data;
	
	public SendPongIntent(byte [] data) {
		this.data = data;
	}
	
	public byte [] getData() {
		return data;
	}
	
}
