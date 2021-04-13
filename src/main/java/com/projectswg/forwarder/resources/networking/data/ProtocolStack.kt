package com.projectswg.forwarder.resources.networking.data

import com.projectswg.forwarder.resources.networking.ClientServer
import com.projectswg.forwarder.resources.networking.packets.Fragmented
import com.projectswg.forwarder.resources.networking.packets.Packet
import com.projectswg.forwarder.resources.networking.packets.SequencedPacket
import me.joshlarson.jlcommon.log.Log
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

class ProtocolStack(val source: InetSocketAddress, val server: ClientServer, private val sendPacket: (InetSocketAddress, ByteArray) -> Unit) {
	
	private val fragmentedProcessor: FragmentedProcessor = FragmentedProcessor()
	private val outboundRaw: BlockingQueue<ByteArray> = LinkedBlockingQueue()
	private val inbound: ConnectionStream<SequencedPacket> = ConnectionStream()
	private val outbound: ConnectionStream<SequencedOutbound> = ConnectionStream()
	private val packager: Packager = Packager(outboundRaw, outbound)
	
	private var pingSource: InetSocketAddress? = null
	var connectionId: Int = 0
	
	val rxSourceSequence: Long
		get() = inbound.sourceSequence
	
	val rxSequence: Short
		get() = inbound.getSequence()
	
	val txSourceSequence: Long
		get() = outbound.sourceSequence
	
	val txSequence: Short
		get() = outbound.getSequence()
	
	val outboundPending: Int
		get() = outboundRaw.size
	
	fun send(packet: Packet) {
		Log.t("Sending %s", packet)
		send(packet.encode().array())
	}
	
	fun send(data: ByteArray) {
		sendPacket(source, data)
	}
	
	fun sendPing(data: ByteArray) {
		val pingSource = this.pingSource
		if (pingSource != null)
			sendPacket(pingSource, data)
	}
	
	fun getNextIncoming(): SequencedPacket? = inbound.poll()
	
	fun getFirstUnacknowledgedOutbound(): Short {
		val out = outbound.peek() ?: return -1
		return out.sequence
	}
	
	fun setPingSource(source: InetSocketAddress) {
		this.pingSource = source
	}
	
	fun addIncoming(packet: SequencedPacket): SequencedStatus {
		return inbound.addUnordered(packet)
	}
	
	fun addFragmented(frag: Fragmented): ByteArray? {
		return fragmentedProcessor.addFragmented(frag)
	}
	
	fun addOutbound(data: ByteArray) {
		try {
			outboundRaw.put(data)
		} catch (e: InterruptedException) {
			throw RuntimeException(e)
		}
		
	}
	
	fun clearAcknowledgedOutbound(sequence: Short) {
		outbound.removeOrdered(sequence)
	}
	
	@Synchronized
	fun fillOutboundPackagedBuffer(maxPackaged: Int) {
		packager.handle(maxPackaged)
	}
	
	@Synchronized
	fun fillOutboundBuffer(buffer: Array<SequencedOutbound?>): Int {
		return outbound.fillBuffer(buffer)
	}
	
	override fun toString(): String {
		return String.format("ProtocolStack[server=%s, source=%s, connectionId=%d]", server, source, connectionId)
	}
	
	class ConnectionStream<T : SequencedPacket> {
		
		private val sequenced: PriorityQueue<T> = PriorityQueue()
		private val queued: PriorityQueue<T> = PriorityQueue()
		
		var sourceSequence: Long = 0
			private set
		
		fun getSequence(): Short {
			return sourceSequence.toShort()
		}
		
		@Synchronized
		fun addUnordered(packet: T): SequencedStatus {
			if (SequencedPacket.compare(getSequence(), packet.sequence) > 0) {
				val peek = peek()
				return if (peek != null && peek.sequence == getSequence()) SequencedStatus.READY else SequencedStatus.STALE
			}
			
			if (packet.sequence == getSequence()) {
				sequenced.add(packet)
				sourceSequence++
				
				// Add queued OOO packets
				while (true) {
					val queue = queued.peek()
					if (queue == null || queue.sequence != getSequence())
						break
					sequenced.add(queued.poll())
					sourceSequence++
				}
				
				return SequencedStatus.READY
			} else {
				queued.add(packet)
				
				return SequencedStatus.OUT_OF_ORDER
			}
		}
		
		@Synchronized
		fun addOrdered(packet: T) {
			packet.sequence = getSequence()
			addUnordered(packet)
		}
		
		@Synchronized
		fun removeOrdered(sequence: Short) {
			val sequencesRemoved = ArrayList<Short>()
			while (true) {
				val packet = sequenced.peek()
				if (packet == null || SequencedPacket.compare(sequence, packet.sequence) < 0)
					break
				val removed = sequenced.poll()
				assert(packet === removed)
				sequencesRemoved.add(packet.sequence)
			}
			Log.t("Removed acknowledged: %s", sequencesRemoved)
		}
		
		@Synchronized
		fun peek(): T? {
			return sequenced.peek()
		}
		
		@Synchronized
		fun poll(): T? {
			return sequenced.poll()
		}
		
		@Synchronized
		fun fillBuffer(buffer: Array<T?>): Int {
			var n = 0
			for (packet in sequenced) {
				if (n >= buffer.size)
					break
				buffer[n++] = packet
			}
			return n
		}
		
		fun size(): Int {
			return sequenced.size
		}
		
	}
	
	enum class SequencedStatus {
		READY,
		OUT_OF_ORDER,
		STALE
	}
	
}
