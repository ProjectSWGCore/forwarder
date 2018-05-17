package com.projectswg.forwarder.services.crash;

import me.joshlarson.jlcommon.control.Manager;
import me.joshlarson.jlcommon.control.ManagerStructure;

@ManagerStructure(children = {
		PacketRecordingService.class,
		IntentRecordingService.class
})
public class CrashManager extends Manager {
	
}
