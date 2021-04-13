package com.projectswg.forwarder.services.client

import com.projectswg.common.network.packets.PacketType
import com.projectswg.common.network.packets.swg.zone.HeartBeat
import com.projectswg.forwarder.intents.DataPacketInboundIntent
import com.projectswg.forwarder.intents.SonyPacketInboundIntent
import com.projectswg.forwarder.resources.networking.data.ProtocolStack
import com.projectswg.forwarder.resources.networking.packets.*
import me.joshlarson.jlcommon.control.IntentChain
import me.joshlarson.jlcommon.control.IntentHandler
import me.joshlarson.jlcommon.control.IntentMultiplexer
import me.joshlarson.jlcommon.control.IntentMultiplexer.Multiplexer
import me.joshlarson.jlcommon.control.Service
import me.joshlarson.jlcommon.log.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ClientInboundDataService : Service() {
	
	private val multiplexer: IntentMultiplexer = IntentMultiplexer(this, ProtocolStack::class.java, Packet::class.java)
	private val intentChain: IntentChain = IntentChain()
	
	@IntentHandler
	private fun handleSonyPacketInboundIntent(spii: SonyPacketInboundIntent) {
		multiplexer.call(spii.stack, spii.packet)
	}
	
	@Multiplexer
	private fun handlePingPacket(stack: ProtocolStack, ping: PingPacket) {
		onData(HeartBeat(ping.payload).encode().array())
	}
	
	@Multiplexer
	private fun handleRawSwgPacket(stack: ProtocolStack, data: RawSWGPacket) {
		onData(data.rawData)
	}
	
	@Multiplexer
	private fun handleDataChannel(stack: ProtocolStack, data: DataChannel) {
		when (stack.addIncoming(data)) {
			ProtocolStack.SequencedStatus.READY -> readAvailablePackets(stack)
			ProtocolStack.SequencedStatus.OUT_OF_ORDER -> {
				Log.d("Data/Inbound Received Data Out of Order %d", data.sequence)
				for (seq in stack.rxSequence until data.sequence)
					stack.send(OutOfOrder(seq.toShort()))
			}
			else -> { }
		}
	}
	
	@Multiplexer
	private fun handleFragmented(stack: ProtocolStack, frag: Fragmented) {
		when (stack.addIncoming(frag)) {
			ProtocolStack.SequencedStatus.READY -> readAvailablePackets(stack)
			ProtocolStack.SequencedStatus.OUT_OF_ORDER -> {
				Log.d("Data/Inbound Received Frag Out of Order %d", frag.sequence)
				for (seq in stack.rxSequence until frag.sequence)
					stack.send(OutOfOrder(seq.toShort()))
			}
			else -> { }
		}
	}
	
	private fun readAvailablePackets(stack: ProtocolStack) {
		var highestSequence: Short = -1
		var updatedSequence = false
		var packet = stack.getNextIncoming()
		while (packet != null) {
			Log.t("Data/Inbound Received: %s", packet)
			if (packet is DataChannel) {
				for (data in packet.packets)
					onData(data)
			} else if (packet is Fragmented) {
				val data = stack.addFragmented((packet as Fragmented?)!!)
				if (data != null)
					onData(data)
			}
			highestSequence = packet.sequence
			packet = stack.getNextIncoming()
			updatedSequence = true
		}
		if (updatedSequence)
			stack.send(Acknowledge(highestSequence))
	}
	
	private fun onData(data: ByteArray) {
		val type = PacketType.fromCrc(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(2))
		Log.d("Data/Inbound Received Data: %s", type)
		intentChain.broadcastAfter(intentManager, DataPacketInboundIntent(data))
	}
	
}
