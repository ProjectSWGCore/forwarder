package com.projectswg.forwarder.intents.client;

import me.joshlarson.jlcommon.control.Intent;
import org.jetbrains.annotations.NotNull;

public class DataPacketInboundIntent extends Intent {
	
	private final byte [] data;
	
	public DataPacketInboundIntent(@NotNull byte [] data) {
		this.data = data;
	}
	
	@NotNull
	public byte [] getData() {
		return data;
	}
	
}
