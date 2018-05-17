package com.projectswg.forwarder.intents.control;

import com.projectswg.forwarder.Forwarder.ForwarderData;
import me.joshlarson.jlcommon.control.Intent;

public class StartForwarderIntent extends Intent {
	
	private final ForwarderData data;
	
	public StartForwarderIntent(ForwarderData data) {
		this.data = data;
	}
	
	public ForwarderData getData() {
		return data;
	}
	
	public static void broadcast(ForwarderData data) {
		new StartForwarderIntent(data).broadcast();
	}
}
