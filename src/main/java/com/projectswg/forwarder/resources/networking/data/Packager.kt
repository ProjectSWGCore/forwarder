package com.projectswg.forwarder.resources.networking.data

import com.projectswg.forwarder.resources.networking.data.ProtocolStack.ConnectionStream
import com.projectswg.forwarder.resources.networking.packets.DataChannel
import com.projectswg.forwarder.resources.networking.packets.Fragmented
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class Packager(private val outboundRaw: BlockingQueue<ByteArray>, private val outboundPackaged: ConnectionStream<SequencedOutbound>) {
	
	private val size: AtomicInteger = AtomicInteger(8)
	private val dataChannel: MutableList<ByteArray> = ArrayList()
	
	fun handle(maxPackaged: Int) {
		var packet: ByteArray?
		var packetSize: Int
		
		while (outboundPackaged.size() < maxPackaged) {
			packet = outboundRaw.poll()
			if (packet == null)
				break
			
			packetSize = getPacketLength(packet)
			
			if (size.get() + packetSize >= 16384) // max data channel size
				sendDataChannel()
			
			if (packetSize < 16384) { // if overflowed, must go into fragmented
				addToDataChannel(packet, packetSize)
			} else {
				sendFragmented(packet)
			}
		}
		sendDataChannel()
	}
	
	private fun addToDataChannel(packet: ByteArray, packetSize: Int) {
		dataChannel.add(packet)
		size.getAndAdd(packetSize)
	}
	
	private fun sendDataChannel() {
		if (dataChannel.isEmpty())
			return
		
		outboundPackaged.addOrdered(SequencedOutbound(DataChannel(dataChannel)))
		reset()
	}
	
	private fun sendFragmented(packet: ByteArray) {
		val frags = Fragmented.split(packet)
		for (frag in frags) {
			outboundPackaged.addOrdered(SequencedOutbound(Fragmented(0.toShort(), frag)))
		}
	}
	
	private fun reset() {
		dataChannel.clear()
		size.set(8)
	}
	
	private fun getPacketLength(data: ByteArray): Int {
		val len = data.size
		return if (len >= 255) len + 3 else len + 1
	}
	
}
