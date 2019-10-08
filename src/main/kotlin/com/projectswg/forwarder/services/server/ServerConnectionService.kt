package com.projectswg.forwarder.services.server

import com.projectswg.common.network.NetBuffer
import com.projectswg.common.network.packets.PacketType
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStopped
import com.projectswg.common.network.packets.swg.holo.login.HoloLoginResponsePacket
import com.projectswg.common.network.packets.swg.login.*
import com.projectswg.forwarder.Forwarder.ForwarderData
import com.projectswg.forwarder.intents.*
import com.projectswg.forwarder.resources.networking.NetInterceptor
import com.projectswg.holocore.client.HolocoreSocket
import me.joshlarson.jlcommon.concurrency.BasicThread
import me.joshlarson.jlcommon.control.IntentChain
import me.joshlarson.jlcommon.control.IntentHandler
import me.joshlarson.jlcommon.control.Service
import me.joshlarson.jlcommon.log.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ServerConnectionService : Service() {
	
	private val intentChain: IntentChain = IntentChain()
	private val thread: BasicThread = BasicThread("server-connection", Runnable { this.primaryConnectionLoop() })
	
	private lateinit var interceptor: NetInterceptor // set by StartForwarderIntent
	private lateinit var data: ForwarderData         // set by StartForwarderIntent
	private var holocore: HolocoreSocket? = null
	
	override fun stop(): Boolean {
		return stopRunningLoop(HoloConnectionStopped.ConnectionStoppedReason.APPLICATION)
	}
	
	@IntentHandler
	private fun handleStartForwarderIntent(sfi: StartForwarderIntent) {
		interceptor = NetInterceptor(sfi.data)
		data = sfi.data
	}
	
	@IntentHandler
	private fun handleStopForwarderIntent(sfi: StopForwarderIntent) {
		stopRunningLoop(if (data.crashed) HoloConnectionStopped.ConnectionStoppedReason.CRASH else HoloConnectionStopped.ConnectionStoppedReason.APPLICATION)
	}
	
	@IntentHandler
	private fun handleRequestServerConnectionIntent(rsci: RequestServerConnectionIntent) {
		val holocore = this.holocore
		if (holocore != null)
			return  // It's trying to connect - give it a little more time
		
		if (stopRunningLoop(HoloConnectionStopped.ConnectionStoppedReason.NEW_CONNECTION))
			thread.start()
	}
	
	@IntentHandler
	private fun handleClientDisconnectedIntent(cdi: ClientDisconnectedIntent) {
		stopRunningLoop(HoloConnectionStopped.ConnectionStoppedReason.APPLICATION)
	}
	
	@IntentHandler
	private fun handleDataPacketInboundIntent(dpii: DataPacketInboundIntent) {
		val holocore = this.holocore ?: return
		val data = dpii.data
		if (data.size < 6) {
			return // not a valid packet
		}
		val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
		
		when (PacketType.fromCrc(bb.getInt(2))) {
			PacketType.LOGIN_CLIENT_ID -> {
				val loginClientId = LoginClientId(NetBuffer.wrap(bb))
				if (loginClientId.username == this.data.username && loginClientId.password.isEmpty())
					loginClientId.password = this.data.password
				holocore.send(loginClientId.encode().array())
			}
			else -> holocore.send(data)
		}
	}
	
	private fun stopRunningLoop(reason: HoloConnectionStopped.ConnectionStoppedReason): Boolean {
		if (!thread.isExecuting)
			return true
		thread.stop(true)
		Log.d("Terminating connection with the server. Reason: $reason")
		holocore?.send(HoloConnectionStopped(reason).encode().array())
		holocore?.close()
		return thread.awaitTermination(1000)
	}
	
	private fun primaryConnectionLoop() {
		var didConnect = false
		try {
			val address = data.address ?: return
			HolocoreSocket(address.address, address.port, data.isVerifyServer, data.isEncryptionEnabled).use { holocore ->
				this.holocore = holocore
				Log.t("Attempting to connect to server at %s", holocore.remoteAddress)
				try {
					holocore.connect(CONNECT_TIMEOUT)
				} catch (e: IOException) {
					Log.e("Failed to connect to server")
					return
				}
				didConnect = true
				intentChain.broadcastAfter(intentManager, ServerConnectedIntent())
				Log.i("Successfully connected to server at %s", holocore.remoteAddress)
				
				while (holocore.isConnected) {
					if (primaryConnectionLoopReceive(holocore))
						continue // More packets to receive
					
					if (holocore.isConnected)
						Log.w("Server connection interrupted")
					else
						Log.w("Server closed connection!")
					return
				}
			}
		} catch (t: Throwable) {
			Log.w("Caught unknown exception in server connection! %s: %s", t.javaClass.name, t.message)
			Log.w(t)
		} finally {
			Log.i("Disconnected from server.")
			if (didConnect)
				intentChain.broadcastAfter(intentManager, ServerDisconnectedIntent())
			this.holocore = null
		}
	}
	
	private fun primaryConnectionLoopReceive(holocore: HolocoreSocket): Boolean {
		val inbound = holocore.receive() ?: return false
		val data = inbound.data
		
		if (data.size < 6)
			return true
		val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
		when (PacketType.fromCrc(bb.getInt(2))) {
			PacketType.LOGIN_CLUSTER_STATUS -> {
				val cluster = LoginClusterStatus(NetBuffer.wrap(data))
				for (g in cluster.galaxies) {
					g.address = "127.0.0.1"
					g.zonePort = this.data.zonePort
					g.pingPort = this.data.pingPort
				}
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(cluster.encode().array()))
			}
			PacketType.HOLO_LOGIN_RESPONSE -> {
				val response = HoloLoginResponsePacket(NetBuffer.wrap(data))
				for (g in response.galaxies) {
					g.address = "127.0.0.1"
					g.zonePort = this.data.zonePort
					g.pingPort = this.data.pingPort
				}
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(LoginClientToken(ByteArray(24), 0, "").encode().array()))
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(CharacterCreationDisabled().encode().array()))
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(LoginEnumCluster(response.galaxies, 2).encode().array()))
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(EnumerateCharacterId(response.characters).encode().array()))
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(LoginClusterStatus(response.galaxies).encode().array()))
			}
			else -> {
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(interceptor.interceptServer(inbound.data)))
			}
		}
		return true
	}
	
	companion object {
		
		private const val CONNECT_TIMEOUT = 5000
		
	}
	
}
