package com.projectswg.forwarder.intents.client;

import me.joshlarson.jlcommon.control.Intent;
import org.jetbrains.annotations.NotNull;

public class DataPacketOutboundIntent extends Intent {
	
	private final byte [] data;
	
	public DataPacketOutboundIntent(@NotNull byte [] data) {
		this.data = data;
	}
	
	@NotNull
	public byte [] getData() {
		return data;
	}
	
}
