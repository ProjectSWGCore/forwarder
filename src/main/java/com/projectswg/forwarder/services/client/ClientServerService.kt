package com.projectswg.forwarder.services.client

import com.projectswg.common.network.NetBuffer
import com.projectswg.forwarder.Forwarder.ForwarderData
import com.projectswg.forwarder.intents.*
import com.projectswg.forwarder.resources.networking.ClientServer
import com.projectswg.forwarder.resources.networking.data.ProtocolStack
import com.projectswg.forwarder.resources.networking.packets.*
import com.projectswg.forwarder.resources.networking.packets.Disconnect.DisconnectReason
import me.joshlarson.jlcommon.control.IntentChain
import me.joshlarson.jlcommon.control.IntentHandler
import me.joshlarson.jlcommon.control.Service
import me.joshlarson.jlcommon.log.Log
import me.joshlarson.jlcommon.network.UDPServer
import me.joshlarson.jlcommon.utilities.ByteUtilities
import java.net.*
import java.nio.BufferUnderflowException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ClientServerService : Service() {
	
	private val intentChain: IntentChain = IntentChain()
	private val stacks: MutableMap<ClientServer, ProtocolStack> = EnumMap(ClientServer::class.java)
	private val serverConnection: AtomicBoolean = AtomicBoolean(false)
	private val cachedSessionRequest: AtomicReference<SessionRequest> = AtomicReference<SessionRequest>(null)
	private val lastPing: AtomicReference<InetSocketAddress> = AtomicReference<InetSocketAddress>(null)
	
	private var data: ForwarderData? = null
	private var loginServer: UDPServer? = null
	private var zoneServer: UDPServer? = null
	private var pingServer: UDPServer? = null
	
	override fun isOperational(): Boolean {
		if (data == null)
			return true
		if (loginServer?.isRunning != true)
			return false
		if (zoneServer?.isRunning != true)
			return false
		return pingServer?.isRunning == true
	}
	
	override fun stop(): Boolean {
		closeConnection(ClientServer.LOGIN)
		closeConnection(ClientServer.ZONE)
		return true
	}
	
	@IntentHandler
	private fun handleStartForwarderIntent(sfi: StartForwarderIntent) {
		var loginServer: UDPServer? = null
		var zoneServer: UDPServer? = null
		var pingServer: UDPServer? = null
		try {
			val data = sfi.data
			Log.t("Initializing login udp server...")
			loginServer = UDPServer(InetSocketAddress(InetAddress.getLoopbackAddress(), data.loginPort), 16384) { this.onLoginPacket(it) }
			Log.t("Initializing zone udp server...")
			zoneServer = UDPServer(InetSocketAddress(InetAddress.getLoopbackAddress(), data.zonePort), 16384) { this.onZonePacket(it) }
			Log.t("Initializing ping udp server...")
			pingServer = UDPServer(InetSocketAddress(InetAddress.getLoopbackAddress(), data.pingPort), 16384) { this.onPingPacket(it) }
			
			Log.t("Binding to login server...")
			loginServer.bind { this.customizeUdpServer(it) }
			Log.t("Binding to zone server...")
			zoneServer.bind { this.customizeUdpServer(it) }
			Log.t("Binding to ping server...")
			pingServer.bind { this.customizeUdpServer(it) }
			
			this.loginServer = loginServer
			this.zoneServer = zoneServer
			this.pingServer = pingServer
			data.loginPort = loginServer.port
			data.zonePort = zoneServer.port
			data.pingPort = pingServer.port
			
			Log.i("Initialized login (%d), zone (%d), and ping (%d) servers", loginServer.port, zoneServer.port, pingServer.port)
		} catch (e: SocketException) {
			Log.a(e)
			loginServer?.close()
			zoneServer?.close()
			pingServer?.close()
		}
		
		data = sfi.data
	}
	
	@IntentHandler
	private fun handleStopForwarderIntent(sfi: StopForwarderIntent) {
		Log.t("Closing the login udp server...")
		loginServer?.close()
		loginServer = null
		Log.t("Closing the zone udp server...")
		zoneServer?.close()
		zoneServer = null
		Log.t("Closing the ping udp server...")
		pingServer?.close()
		pingServer = null
		Log.i("Closed the login, zone, and ping udp servers")
	}
	
	@IntentHandler
	private fun handleServerConnectedIntent(sci: ServerConnectedIntent) {
		serverConnection.set(true)
		val request = this.cachedSessionRequest.getAndSet(null)
		if (request != null) {
			val data = request.encode().array()
			onLoginPacket(DatagramPacket(data, data.size, InetSocketAddress(request.address, request.port)))
		}
	}
	
	@IntentHandler
	private fun handleServerDisconnectedIntent(sdi: ServerDisconnectedIntent) {
		serverConnection.set(false)
		closeConnection(ClientServer.LOGIN)
		closeConnection(ClientServer.ZONE)
		closeConnection(ClientServer.PING)
	}
	
	@IntentHandler
	private fun handleSendPongIntent(spi: SendPongIntent) {
		val lastPing = this.lastPing.get()
		if (lastPing != null)
			send(lastPing, ClientServer.PING, spi.data)
	}
	
	private fun customizeUdpServer(socket: DatagramSocket) {
		try {
			socket.reuseAddress = false
			socket.trafficClass = 0x10
			socket.broadcast = false
			socket.receiveBufferSize = 64 * 1024
			socket.sendBufferSize = 64 * 1024
		} catch (e: SocketException) {
			Log.w(e)
		}
		
	}
	
	private fun onLoginPacket(packet: DatagramPacket) {
		process(packet.socketAddress as InetSocketAddress, ClientServer.LOGIN, packet.data)
	}
	
	private fun onZonePacket(packet: DatagramPacket) {
		process(packet.socketAddress as InetSocketAddress, ClientServer.ZONE, packet.data)
	}
	
	private fun onPingPacket(packet: DatagramPacket) {
		val source = packet.socketAddress as InetSocketAddress
		lastPing.set(source)
		val stack = stacks[ClientServer.ZONE]
		val pingPacket = PingPacket(packet.data)
		pingPacket.address = source.address
		pingPacket.port = source.port
		
		if (stack != null)
			intentChain.broadcastAfter(intentManager, SonyPacketInboundIntent(stack, pingPacket))
	}
	
	private fun send(addr: InetSocketAddress, server: ClientServer, data: ByteArray) {
		when (server) {
			ClientServer.LOGIN -> loginServer?.send(addr, data)
			ClientServer.ZONE -> zoneServer?.send(addr, data)
			ClientServer.PING -> pingServer?.send(addr, data)
		}
	}
	
	private fun process(source: InetSocketAddress, server: ClientServer, data: ByteArray) {
		val parsed: Packet?
		try {
			parsed = if (server === ClientServer.PING) PingPacket(data) else parse(data)
			if (parsed == null)
				return
		} catch (e: BufferUnderflowException) {
			Log.w("Failed to parse packet: %s", ByteUtilities.getHexString(data))
			return
		}
		
		parsed.address = source.address
		parsed.port = source.port
		if (parsed is MultiPacket) {
			for (child in parsed.packets) {
				process(source, server, child)
			}
		} else {
			broadcast(source, server, parsed)
		}
	}
	
	private fun broadcast(source: InetSocketAddress, server: ClientServer, parsed: Packet) {
		val stack = process(source, server, parsed)
		if (stack == null) {
			Log.t("[%s]@%s DROPPED %s", source, server, parsed)
			return
		}
		Log.t("[%s]@%s sent: %s", source, server, parsed)
		intentChain.broadcastAfter(intentManager, SonyPacketInboundIntent(stack, parsed))
	}
	
	private fun process(source: InetSocketAddress, server: ClientServer, parsed: Packet): ProtocolStack? {
		Log.t("Process [%b] %s", serverConnection.get(), parsed)
		if (!serverConnection.get()) {
			if (parsed is SessionRequest) {
				cachedSessionRequest.set(parsed)
				intentChain.broadcastAfter(intentManager, RequestServerConnectionIntent())
			}
			for (serverType in ClientServer.values())
				closeConnection(serverType)
			return null
		}
		if (parsed is SessionRequest)
			return onSessionRequest(source, server, parsed)
		if (parsed is Disconnect)
			return onDisconnect(source, server, parsed)
		
		val stack = stacks[server] ?: return null
		
		if (parsed is PingPacket) {
			stack.setPingSource(source)
		} else if (stack.source != source || stack.server !== server) {
			return null
		}
		
		return stack
	}
	
	private fun onSessionRequest(source: InetSocketAddress, server: ClientServer, request: SessionRequest): ProtocolStack? {
		val current = stacks[server]
		if (current != null && request.connectionId != current.connectionId) {
			closeConnection(server)
			return null
		}
		val stack = ProtocolStack(source, server) { remote, data -> send(remote, server, data) }
		stack.connectionId = request.connectionId
		
		openConnection(server, stack)
		return stack
	}
	
	private fun onDisconnect(source: InetSocketAddress, server: ClientServer, disconnect: Disconnect): ProtocolStack? {
		// Sets the current stack to null if it matches the Disconnect packet
		val stack = stacks[server]
		if (stack != null && stack.source == source && stack.connectionId == disconnect.connectionId) {
			closeConnection(server)
		} else {
			send(source, server, Disconnect(disconnect.connectionId, DisconnectReason.APPLICATION).encode().array())
		}
		return stack
	}
	
	private fun openConnection(server: ClientServer, stack: ProtocolStack) {
		closeConnection(server)
		if (server === ClientServer.LOGIN) {
			closeConnection(ClientServer.ZONE)
			intentChain.broadcastAfter(intentManager, ClientConnectedIntent())
		}
		stacks[server] = stack
		
		stack.send(SessionResponse(stack.connectionId, 0, 0.toByte(), 0.toByte(), 0.toByte(), 16384))
		intentChain.broadcastAfter(intentManager, StackCreatedIntent(stack))
	}
	
	private fun closeConnection(server: ClientServer) {
		val stack = stacks.remove(server) ?: return
		stack.send(Disconnect(stack.connectionId, DisconnectReason.APPLICATION))
		intentChain.broadcastAfter(intentManager, StackDestroyedIntent(stack))
		if (stacks.isEmpty())
			intentChain.broadcastAfter(intentManager, ClientDisconnectedIntent())
	}
	
	private fun parse(rawData: ByteArray): Packet? {
		if (rawData.size < 4)
			return null
		
		val data = NetBuffer.wrap(rawData)
		val opcode = data.netShort.toInt()
		data.position(0)
		when (opcode) {
			0x01 -> return SessionRequest(data)
			0x03 -> return MultiPacket(data)
			0x05 -> return Disconnect(data)
			0x06 -> return KeepAlive(data)
			0x07 -> return ClientNetworkStatusUpdate(data)
			0x09, 0x0A, 0x0B, 0x0C -> return DataChannel(data)
			0x0D, 0x0E, 0x0F, 0x10 -> return Fragmented(data)
			0x11, 0x12, 0x13, 0x14 -> return OutOfOrder(data)
			0x15, 0x16, 0x17, 0x18 -> return Acknowledge(data)
			else -> {
				if (rawData.size >= 6)
					return RawSWGPacket(rawData)
				Log.w("Unknown SOE packet: %d  %s", opcode, ByteUtilities.getHexString(data.array()))
				return null
			}
		}
	}
	
}
