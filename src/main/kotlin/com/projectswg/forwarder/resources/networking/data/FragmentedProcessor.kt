package com.projectswg.forwarder.resources.networking.data

import com.projectswg.common.network.NetBuffer
import com.projectswg.forwarder.resources.networking.packets.Fragmented
import java.util.*

class FragmentedProcessor {
	
	private val fragmentedBuffer: MutableList<Fragmented> = ArrayList()
	
	fun reset() {
		fragmentedBuffer.clear()
	}
	
	fun addFragmented(frag: Fragmented): ByteArray? {
		fragmentedBuffer.add(frag)
		
		val firstFrag = fragmentedBuffer[0]
		val payload = NetBuffer.wrap(firstFrag.payload)
		val length = payload.netInt
		
		return if (((length + 4.0) / 489).toInt() > fragmentedBuffer.size) null else processFragmentedReady(length) // Doesn't have the minimum number of required packets
	}
	
	private fun processFragmentedReady(size: Int): ByteArray {
		val combined = ByteArray(size)
		var index = 0
		while (index < combined.size) {
			val payload = fragmentedBuffer.removeAt(0).payload
			val header = if (index == 0) 4 else 0
			
			System.arraycopy(payload, header, combined, index, payload.size - header)
			index += payload.size - header
		}
		return combined
	}
	
}
