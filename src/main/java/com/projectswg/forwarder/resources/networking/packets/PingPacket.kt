package com.projectswg.forwarder.resources.networking.packets

import com.projectswg.common.network.NetBuffer
import com.projectswg.common.utilities.ByteUtilities

class PingPacket(var payload: ByteArray) : Packet() {
	
	override fun decode(data: NetBuffer) {
		this.payload = data.array()
	}
	
	override fun encode(): NetBuffer {
		return NetBuffer.wrap(payload)
	}
	
	override fun toString(): String {
		return "PingPacket[payload=" + ByteUtilities.getHexString(payload) + "]"
	}
	
}
