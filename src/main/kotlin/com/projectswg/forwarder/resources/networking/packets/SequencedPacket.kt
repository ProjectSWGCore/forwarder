package com.projectswg.forwarder.resources.networking.packets

import com.projectswg.common.network.NetBuffer

interface SequencedPacket : Comparable<SequencedPacket> {
	
	var sequence: Short
	
	fun encode(): NetBuffer
	
	override fun compareTo(other: SequencedPacket): Int {
		return compare(this, other)
	}
	
	companion object {
		
		fun compare(a: SequencedPacket, b: SequencedPacket): Int {
			return compare(a.sequence, b.sequence)
		}
		
		fun compare(a: Short, b: Short): Int {
			val aSeq = a.toInt() and 0xFFFF
			val bSeq = b.toInt() and 0xFFFF
			if (aSeq == bSeq)
				return 0
			
			var diff = bSeq - aSeq
			if (diff <= 0)
				diff += 0x10000
			
			return if (diff < 30000) -1 else 1
		}
	}
	
}
