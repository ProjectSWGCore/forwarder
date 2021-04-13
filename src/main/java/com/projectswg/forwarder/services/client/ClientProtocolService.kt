package com.projectswg.forwarder.services.client

import com.projectswg.forwarder.intents.SonyPacketInboundIntent
import com.projectswg.forwarder.resources.networking.data.ProtocolStack
import com.projectswg.forwarder.resources.networking.packets.ClientNetworkStatusUpdate
import com.projectswg.forwarder.resources.networking.packets.KeepAlive
import com.projectswg.forwarder.resources.networking.packets.Packet
import com.projectswg.forwarder.resources.networking.packets.ServerNetworkStatusUpdate
import me.joshlarson.jlcommon.control.IntentHandler
import me.joshlarson.jlcommon.control.IntentMultiplexer
import me.joshlarson.jlcommon.control.IntentMultiplexer.Multiplexer
import me.joshlarson.jlcommon.control.Service

class ClientProtocolService : Service() {
	
	private val multiplexer: IntentMultiplexer = IntentMultiplexer(this, ProtocolStack::class.java, Packet::class.java)
	
	@IntentHandler
	private fun handleSonyPacketInboundIntent(spii: SonyPacketInboundIntent) {
		multiplexer.call(spii.stack, spii.packet)
	}
	
	@Multiplexer
	private fun handleClientNetworkStatus(stack: ProtocolStack, update: ClientNetworkStatusUpdate) {
		val serverNet = ServerNetworkStatusUpdate()
		serverNet.clientTickCount = update.tick.toShort()
		serverNet.serverSyncStampLong = (System.currentTimeMillis() - GALACTIC_BASE_TIME).toInt()
		serverNet.clientPacketsSent = update.sent
		serverNet.clientPacketsRecv = update.recv
		serverNet.serverPacketsSent = stack.txSourceSequence
		serverNet.serverPacketsRecv = stack.rxSourceSequence
		stack.send(serverNet)
	}
	
	@Multiplexer
	private fun handleKeepAlive(stack: ProtocolStack, keepAlive: KeepAlive) {
		stack.send(KeepAlive())
	}
	
	companion object {
		
		private const val GALACTIC_BASE_TIME = 1323043200
		
	}
	
}
