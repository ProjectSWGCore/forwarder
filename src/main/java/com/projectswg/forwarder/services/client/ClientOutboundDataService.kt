package com.projectswg.forwarder.services.client

import com.projectswg.common.network.NetBuffer
import com.projectswg.common.network.packets.PacketType
import com.projectswg.common.network.packets.swg.zone.HeartBeat
import com.projectswg.forwarder.intents.*
import com.projectswg.forwarder.resources.networking.ClientServer
import com.projectswg.forwarder.resources.networking.data.ProtocolStack
import com.projectswg.forwarder.resources.networking.data.SequencedOutbound
import com.projectswg.forwarder.resources.networking.packets.Acknowledge
import com.projectswg.forwarder.resources.networking.packets.OutOfOrder
import com.projectswg.forwarder.resources.networking.packets.Packet
import me.joshlarson.jlcommon.concurrency.BasicScheduledThread
import me.joshlarson.jlcommon.concurrency.BasicThread
import me.joshlarson.jlcommon.concurrency.Delay
import me.joshlarson.jlcommon.control.IntentHandler
import me.joshlarson.jlcommon.control.IntentMultiplexer
import me.joshlarson.jlcommon.control.IntentMultiplexer.Multiplexer
import me.joshlarson.jlcommon.control.Service
import me.joshlarson.jlcommon.log.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ClientOutboundDataService : Service() {
	
	private val outboundBuffer: Array<SequencedOutbound?> = arrayOfNulls(4096)
	private val multiplexer: IntentMultiplexer = IntentMultiplexer(this, ProtocolStack::class.java, Packet::class.java)
	private val activeStacks: MutableSet<ProtocolStack> = ConcurrentHashMap.newKeySet()
	private val sendThread: BasicThread = BasicThread("outbound-sender") { this.persistentSend() }
	private val heartbeatThread: BasicScheduledThread = BasicScheduledThread("heartbeat") { this.heartbeat() }
	private val zoningIn: AtomicBoolean = AtomicBoolean(false)
	private val packetNotifyLock: ReentrantLock = ReentrantLock()
	private val packetNotify: Condition = packetNotifyLock.newCondition()
	
	override fun terminate(): Boolean {
		if (sendThread.isExecuting)
			sendThread.stop(true)
		if (heartbeatThread.isRunning)
			heartbeatThread.stop()
		return sendThread.awaitTermination(1000) && heartbeatThread.awaitTermination(1000)
	}
	
	@IntentHandler
	private fun handleStartForwarderIntent(sfi: StartForwarderIntent) {
	}
	
	@IntentHandler
	private fun handleSonyPacketInboundIntent(spii: SonyPacketInboundIntent) {
		multiplexer.call(spii.stack, spii.packet)
	}
	
	@IntentHandler
	private fun handleClientConnectedIntent(cci: ClientConnectedIntent) {
		if (sendThread.isExecuting)
			return
		sendThread.start()
		heartbeatThread.startWithFixedRate(0, 500)
	}
	
	@IntentHandler
	private fun handleClientDisconnectedIntent(cdi: ClientDisconnectedIntent) {
		if (!sendThread.isExecuting)
			return
		sendThread.stop(true)
		heartbeatThread.stop()
		sendThread.awaitTermination(1000)
		heartbeatThread.awaitTermination(1000)
	}
	
	@IntentHandler
	private fun handleStackCreatedIntent(sci: StackCreatedIntent) {
		activeStacks.add(sci.stack)
	}
	
	@IntentHandler
	private fun handleStackDestroyedIntent(sdi: StackDestroyedIntent) {
		activeStacks.remove(sdi.stack)
	}
	
	@IntentHandler
	private fun handleDataPacketOutboundIntent(dpoi: DataPacketOutboundIntent) {
		val type = PacketType.fromCrc(ByteBuffer.wrap(dpoi.data).order(ByteOrder.LITTLE_ENDIAN).getInt(2))
		var filterServer: ClientServer? = ClientServer.ZONE
		if (type != null) {
			when (type) {
				PacketType.ERROR_MESSAGE,
				PacketType.SERVER_ID,
				PacketType.SERVER_NOW_EPOCH_TIME -> filterServer = null
				
				PacketType.CMD_START_SCENE -> zoningIn.set(true)
				PacketType.CMD_SCENE_READY -> zoningIn.set(false)
				
				PacketType.LOGIN_CLUSTER_STATUS,
				PacketType.LOGIN_CLIENT_TOKEN,
				PacketType.LOGIN_INCORRECT_CLIENT_ID,
				PacketType.LOGIN_ENUM_CLUSTER,
				PacketType.ENUMERATE_CHARACTER_ID,
				PacketType.CHARACTER_CREATION_DISABLED,
				PacketType.DELETE_CHARACTER_REQUEST,
				PacketType.DELETE_CHARACTER_RESPONSE -> filterServer = ClientServer.LOGIN
				
				PacketType.HEART_BEAT_MESSAGE -> {
					val heartbeat = HeartBeat()
					heartbeat.decode(NetBuffer.wrap(dpoi.data))
					if (heartbeat.payload.isNotEmpty()) {
						SendPongIntent(heartbeat.payload).broadcast(intentManager)
						return
					}
				}
				else -> {}
			}
		}
		val finalFilterServer = filterServer
		val stack = activeStacks.stream().filter { s -> s.server == finalFilterServer }.findFirst().orElse(null)
		if (stack == null) {
			Log.d("Data/Outbound Sending %s [len=%d] to %s", type, dpoi.data.size, activeStacks)
			for (active in activeStacks) {
				active.addOutbound(dpoi.data)
			}
		} else {
			Log.d("Data/Outbound Sending %s [len=%d] to %s", type, dpoi.data.size, stack)
			stack.addOutbound(dpoi.data)
		}
		packetNotifyLock.withLock { packetNotify.signal() }
	}
	
	@Multiplexer
	private fun handleAcknowledgement(stack: ProtocolStack, ack: Acknowledge) {
		Log.t("Data/Outbound Client Acknowledged: %d. Min Sequence: %d", ack.sequence, stack.getFirstUnacknowledgedOutbound())
		stack.clearAcknowledgedOutbound(ack.sequence)
	}
	
	@Multiplexer
	private fun handleOutOfOrder(stack: ProtocolStack, ooo: OutOfOrder) {
		Log.t("Data/Outbound Out of Order: %d. Min Sequence: %d", ooo.sequence, stack.getFirstUnacknowledgedOutbound())
	}
	
	private fun persistentSend() {
		Log.d("Data/Outbound Starting Persistent Send")
		var iteration = 0
		while (!Delay.isInterrupted()) {
			flushPackaged(iteration % 20 == 0)
			iteration++
			if (zoningIn.get()) {
				Delay.sleepMilli(50)
			} else {
				packetNotifyLock.withLock {
					while (!hasPendingPacket() && !Delay.isInterrupted()) {
						try {
							packetNotify.await(1, TimeUnit.SECONDS)
						} catch (e: InterruptedException) {
							break
						}
					}
				}
			}
		}
		Log.d("Data/Outbound Stopping Persistent Send")
	}
	
	private fun hasPendingPacket(): Boolean {
		for (stack in activeStacks) {
			if (stack.outboundPending > 0)
				return true
		}
		return false
	}
	
	private fun heartbeat() {
		for (stack in activeStacks) {
			stack.send(HeartBeat().encode().array())
		}
	}
	
	private fun flushPackaged(overrideSent: Boolean) {
		for (stack in activeStacks) {
			stack.fillOutboundPackagedBuffer(outboundBuffer.size)
			val count = stack.fillOutboundBuffer(outboundBuffer)
			var sent = 0
			for (i in 0 until count) {
				val out = outboundBuffer[i] ?: continue
				if (overrideSent || !out.isSent) {
					stack.send(out.data)
					out.isSent = true
					sent++
				}
			}
			
			if (sent > 0)
				Log.t("Data/Outbound Sent ${outboundBuffer[0]?.sequence} - ${outboundBuffer[count - 1]?.sequence}  [$count]")
			else if (count > 0)
				Log.t("Data/Outbound Waiting to send ${outboundBuffer[0]?.sequence} - ${outboundBuffer[count - 1]?.sequence}  [$count]")
		}
	}
	
}
