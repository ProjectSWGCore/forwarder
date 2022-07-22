package com.projectswg.forwarder.services.server

import com.projectswg.common.network.packets.swg.holo.HoloConnectionStopped
import com.projectswg.forwarder.Forwarder.ForwarderData
import com.projectswg.forwarder.intents.*
import com.projectswg.forwarder.resources.networking.NetInterceptor
import com.projectswg.forwarder.resources.server.HolocoreConnection
import me.joshlarson.jlcommon.concurrency.BasicThread
import me.joshlarson.jlcommon.concurrency.ScheduledThreadPool
import me.joshlarson.jlcommon.control.IntentHandler
import me.joshlarson.jlcommon.control.Service
import me.joshlarson.jlcommon.log.Log
import java.util.concurrent.atomic.AtomicReference

class ServerConnectionService : Service() {
	
	private val thread = BasicThread("server-connection") { this.primaryConnectionLoop() }
	private val keepAliveThread = ScheduledThreadPool(1, "server-keepalive")
	
	private val currentConnection = AtomicReference<HolocoreConnection?>(null)
	
	private lateinit var interceptor: NetInterceptor // set by StartForwarderIntent
	private lateinit var data: ForwarderData         // set by StartForwarderIntent
	
	override fun start(): Boolean {
		keepAliveThread.start()
		keepAliveThread.executeWithFixedDelay(30_000, 30_000) { currentConnection.get()?.ping() }
		return super.start()
	}
	
	override fun stop(): Boolean {
		keepAliveThread.stop()
		val stoppedKeepAlive = keepAliveThread.awaitTermination(1000);
		val stoppedServerConnection = stopRunningLoop(HoloConnectionStopped.ConnectionStoppedReason.APPLICATION)
		
		return stoppedKeepAlive && stoppedServerConnection
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
		if (currentConnection.get()?.getConnectionStatus() == HolocoreConnection.ServerConnectionStatus.CONNECTING)
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
		val data = dpii.data
		if (data.size < 6) {
			return // not a valid packet
		}
		
		currentConnection.get()?.sendPacket(data)
	}
	
	private fun stopRunningLoop(reason: HoloConnectionStopped.ConnectionStoppedReason): Boolean {
		if (!thread.isExecuting)
			return true
		
		currentConnection.get()?.setDisconnectReason(reason)
		Log.d("Terminating connection with the server. Reason: $reason")
		thread.stop(true)
		return thread.awaitTermination(5000)
	}
	
	private fun primaryConnectionLoop() {
		try {
			HolocoreConnection(intentManager, interceptor, data).use {
				currentConnection.set(it)
				it.handle()
				currentConnection.set(null)
			}
		} catch (t: Throwable) {
			Log.w("Caught unknown exception in server connection! %s: %s", t.javaClass.name, t.message)
			Log.w(t)
		}
	}
	
}
