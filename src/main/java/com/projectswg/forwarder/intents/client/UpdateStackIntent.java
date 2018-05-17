package com.projectswg.forwarder.intents.client;

import com.projectswg.forwarder.resources.networking.data.ProtocolStack;
import me.joshlarson.jlcommon.control.Intent;

import javax.annotation.CheckForNull;

public class UpdateStackIntent extends Intent {
	
	private final ProtocolStack stack;
	
	public UpdateStackIntent(ProtocolStack stack) {
		this.stack = stack;
	}
	
	@CheckForNull
	public ProtocolStack getStack() {
		return stack;
	}
	
}
