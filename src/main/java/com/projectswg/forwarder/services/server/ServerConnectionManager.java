package com.projectswg.forwarder.services.server;

import me.joshlarson.jlcommon.control.Manager;
import me.joshlarson.jlcommon.control.ManagerStructure;

@ManagerStructure(children = {
		ServerConnectionService.class
})
public class ServerConnectionManager extends Manager {
	
}
