package com.projectswg.forwarder.resources.networking.packets

import com.projectswg.common.network.NetBuffer


class SeriousErrorAcknowledge : Packet {
	
	constructor()
	constructor(data: NetBuffer) {
		decode(data)
	}
	
	override fun decode(data: NetBuffer) {
		data.position(2)
	}
	
	override fun encode(): NetBuffer {
		val data = NetBuffer.allocate(2)
		data.addNetShort( 0x1D)
		return data
	}
	
}
