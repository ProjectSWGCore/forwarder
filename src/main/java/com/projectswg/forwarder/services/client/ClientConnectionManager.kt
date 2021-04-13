package com.projectswg.forwarder.services.client

import me.joshlarson.jlcommon.control.Manager
import me.joshlarson.jlcommon.control.ManagerStructure

@ManagerStructure(children = [
	ClientInboundDataService::class,
	ClientOutboundDataService::class,
	ClientProtocolService::class,
	ClientServerService::class
])
class ClientConnectionManager : Manager()
