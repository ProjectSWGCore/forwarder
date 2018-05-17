package com.projectswg.forwarder;

import com.projectswg.forwarder.services.client.ClientConnectionManager;
import com.projectswg.forwarder.services.crash.CrashManager;
import com.projectswg.forwarder.services.server.ServerConnectionManager;
import me.joshlarson.jlcommon.control.Manager;
import me.joshlarson.jlcommon.control.ManagerStructure;

@ManagerStructure(children = {
		ClientConnectionManager.class,
		ServerConnectionManager.class,
		CrashManager.class
})
public class ConnectionManager extends Manager {
	
}
