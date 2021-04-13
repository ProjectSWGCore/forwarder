package com.projectswg.forwarder.resources.networking.data

import com.projectswg.common.network.NetBuffer
import com.projectswg.forwarder.resources.networking.packets.SequencedPacket

class SequencedOutbound(private val packet: SequencedPacket) : SequencedPacket {
	
	var data: ByteArray = packet.encode().array()
		private set
	var isSent: Boolean = false
	
	override var sequence: Short
		get() = packet.sequence
		set(sequence) {
			this.packet.sequence = sequence
			this.data = packet.encode().array()
		}
	
	override fun encode(): NetBuffer {
		return packet.encode()
	}
	
}
