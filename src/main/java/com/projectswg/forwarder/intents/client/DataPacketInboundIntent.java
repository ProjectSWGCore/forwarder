package com.projectswg.forwarder.intents.client;

import me.joshlarson.jlcommon.control.Intent;

import javax.annotation.Nonnull;

public class DataPacketInboundIntent extends Intent {
	
	private final byte [] data;
	
	public DataPacketInboundIntent(@Nonnull byte [] data) {
		this.data = data;
	}
	
	@Nonnull
	public byte [] getData() {
		return data;
	}
	
}
