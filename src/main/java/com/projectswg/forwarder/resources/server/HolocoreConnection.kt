package com.projectswg.forwarder.resources.server

import com.projectswg.common.network.NetBuffer
import com.projectswg.common.network.packets.PacketType
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStarted
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStopped
import com.projectswg.common.network.packets.swg.holo.login.HoloLoginResponsePacket
import com.projectswg.common.network.packets.swg.login.*
import com.projectswg.forwarder.Forwarder
import com.projectswg.forwarder.intents.DataPacketOutboundIntent
import com.projectswg.forwarder.intents.ServerConnectedIntent
import com.projectswg.forwarder.intents.ServerDisconnectedIntent
import com.projectswg.forwarder.resources.networking.NetInterceptor
import me.joshlarson.jlcommon.concurrency.Delay
import me.joshlarson.jlcommon.control.IntentChain
import me.joshlarson.jlcommon.control.IntentManager
import me.joshlarson.jlcommon.log.Log
import me.joshlarson.websocket.client.WebSocketClientCallback
import me.joshlarson.websocket.client.WebSocketClientProtocol
import me.joshlarson.websocket.common.WebSocketHandler
import me.joshlarson.websocket.common.parser.http.HttpResponse
import me.joshlarson.websocket.common.parser.websocket.WebSocketCloseReason
import me.joshlarson.websocket.common.parser.websocket.WebsocketFrame
import me.joshlarson.websocket.common.parser.websocket.WebsocketFrameType
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.withLock

class HolocoreConnection(private val intentManager: IntentManager,
                         private val interceptor: NetInterceptor,
                         private val forwarderData: Forwarder.ForwarderData) : Closeable {
	
	private val intentChain: IntentChain = IntentChain()
	private val upgradeDelaySemaphore = Semaphore(0)
	private val outputStreamLock = ReentrantLock()
	private val connectionStatus = AtomicReference(ServerConnectionStatus.DISCONNECTED)
	private val disconnectReason = AtomicReference(HoloConnectionStopped.ConnectionStoppedReason.UNKNOWN)
	private val connectionSender = AtomicReference<((ByteArray) -> Unit)?>(null)
	
	private val initialConnectionUri = forwarderData.connectionUri
	private val socket: Socket = createSocket(initialConnectionUri)
	private val port: Int = when {
		initialConnectionUri.port != -1 -> initialConnectionUri.port
		initialConnectionUri.scheme == "wss" -> 443
		initialConnectionUri.scheme == "ws" -> 80
		else -> {
			Log.e("Undefined port in connection URI")
			throw IllegalArgumentException("initialConnectionUri")
		}
	}
	
	private lateinit var connectionUri: URI
	private lateinit var outputStream: OutputStream
	private lateinit var wsProtocol: WebSocketClientProtocol
	
	override fun close() {
		socket.close()
	}
	
	fun getConnectionStatus(): ServerConnectionStatus {
		return connectionStatus.get()
	}
	
	fun setDisconnectReason(reason: HoloConnectionStopped.ConnectionStoppedReason) {
		this.disconnectReason.set(reason)
	}
	
	fun sendPacket(packet: ByteArray) {
		val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
		
		when (PacketType.fromCrc(bb.getInt(2))) {
			PacketType.LOGIN_CLIENT_ID -> {
				val loginClientId = LoginClientId(NetBuffer.wrap(bb))
				if (loginClientId.username == this.forwarderData.username && loginClientId.password.isEmpty())
					loginClientId.password = this.forwarderData.password
				
				Log.i("Received login packet for %s", loginClientId.username)
				forwarderData.username = loginClientId.username
				forwarderData.password = loginClientId.password
				forwarderData.protocolVersion = PROTOCOL_VERSION
				upgradeDelaySemaphore.drainPermits()
				upgradeDelaySemaphore.release()
				connectionSender.get()?.invoke(loginClientId.encode().array())
			}
			else -> {
				connectionSender.get()?.invoke(packet)
			}
		}
	}
	
	fun handle() {
		if (!connectAndUpgrade())
			return
		
		handleOnConnect()
		try {
			handleReadLoop()
			startGracefulDisconnect()
		} finally {
			handleOnDisconnect()
		}
	}
	
	private fun connectAndUpgrade(): Boolean {
		Log.t("Attempting to connect to server at %s://%s:%d", initialConnectionUri.scheme, initialConnectionUri.host, port)
		try {
			socket.connect(InetSocketAddress(initialConnectionUri.host, port), CONNECT_TIMEOUT)
		} catch (e: IOException) {
			Log.w("Failed to connect to server: %s", e.message)
			return false
		} catch (e: InterruptedException) {
			Log.w("Server connection timed out")
			return false
		}
		intentChain.broadcastAfter(intentManager, ServerConnectedIntent())
		
		Log.t("Connected to server via TCP - awaiting login packet...")
		upgradeDelaySemaphore.drainPermits() // Ensure we have exactly zero permits
		try {
			upgradeDelaySemaphore.tryAcquire(30, TimeUnit.SECONDS)
		} catch (e: InterruptedException) {
			Log.w("No login packet received within 30s - terminating connection")
			return false
		}
		
		Log.t("Received login packet - upgrading to websocket")
		connectionUri = forwarderData.connectionUri
		outputStream = socket.getOutputStream()
		wsProtocol = WebSocketClientProtocol(WebsocketHandler(), connectionUri.rawPath + "?" + connectionUri.rawQuery, ::writeToOutputStream, socket::close)
		return true
	}
	
	private fun handleOnConnect() {
		wsProtocol.onConnect()
	}
	
	private fun handleReadLoop() {
		val inputStream = socket.getInputStream()
		val buffer = ByteArray(4096)
		val startOfConnection = System.nanoTime()
		
		while (!Delay.isInterrupted()) {
			val n = inputStream.read(buffer)
			if (n <= 0)
				break
			
			wsProtocol.onRead(buffer, 0, n)
			
			if (connectionStatus.get() == ServerConnectionStatus.CONNECTING && System.nanoTime() - startOfConnection >= CONNECT_TIMEOUT) {
				Log.e("Failed to connect to server")
				return
			}
		}
	}
	
	private fun startGracefulDisconnect() {
		val inputStream = socket.getInputStream()
		val buffer = ByteArray(4096)
		
		connectionStatus.set(ServerConnectionStatus.DISCONNECTING)
		wsProtocol.send(WebsocketFrame(WebsocketFrameType.BINARY, HoloConnectionStopped(disconnectReason.get()).encode().array()))
		wsProtocol.sendClose()
		
		val startOfDisconnect = System.nanoTime()
		socket.soTimeout = 3000
		while (connectionStatus.get() != ServerConnectionStatus.DISCONNECTED && System.nanoTime() - startOfDisconnect < 5e9) {
			val n = inputStream.read(buffer)
			if (n <= 0)
				break
			
			wsProtocol.onRead(buffer, 0, n)
		}
	}
	
	private fun handlePacket(data: ByteArray) {
		val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
		when (PacketType.fromCrc(bb.getInt(2))) {
			PacketType.LOGIN_CLUSTER_STATUS -> {
				val cluster = LoginClusterStatus(NetBuffer.wrap(data))
				for (g in cluster.galaxies) {
					g.address = "127.0.0.1"
					g.zonePort = this.forwarderData.zonePort
					g.pingPort = this.forwarderData.pingPort
				}
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(cluster.encode().array()))
			}
			PacketType.HOLO_LOGIN_RESPONSE -> {
				val response = HoloLoginResponsePacket(NetBuffer.wrap(data))
				for (g in response.galaxies) {
					g.address = "127.0.0.1"
					g.zonePort = this.forwarderData.zonePort
					g.pingPort = this.forwarderData.pingPort
				}
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(LoginClientToken(ByteArray(24), 0, "").encode().array()))
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(CharacterCreationDisabled().encode().array()))
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(LoginEnumCluster(response.galaxies, 2).encode().array()))
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(EnumerateCharacterId(response.characters).encode().array()))
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(LoginClusterStatus(response.galaxies).encode().array()))
			}
			else -> {
				intentChain.broadcastAfter(intentManager, DataPacketOutboundIntent(interceptor.interceptServer(data)))
			}
		}
	}
	
	private fun handleOnDisconnect() {
		wsProtocol.onDisconnect()
		
		if (connectionStatus.get() != ServerConnectionStatus.DISCONNECTED) {
			Log.w("Server connection interrupted")
		} else {
			Log.i("Successfully closed server connection")
		}
		intentChain.broadcastAfter(intentManager, ServerDisconnectedIntent())
	}
	
	private fun writeToOutputStream(data: ByteArray) {
		outputStreamLock.withLock {
			outputStream.write(data)
		}
	}
	
	private inner class WebsocketHandler : WebSocketClientCallback {
		override fun onUpgrade(obj: WebSocketHandler, response: HttpResponse) {
			connectionSender.set(obj::sendBinary)
		}
		
		override fun onDisconnect(obj: WebSocketHandler, closeCode: Int, reason: String) {
			connectionStatus.set(ServerConnectionStatus.DISCONNECTED)
		}
		
		override fun onBinaryMessage(obj: WebSocketHandler, rawData: ByteArray) {
			val dataBuffer = NetBuffer.wrap(rawData)
			dataBuffer.short
			when (dataBuffer.int) {
				HoloConnectionStarted.CRC -> {
					Log.i("Successfully connected to server at %s", connectionUri.toASCIIString())
					connectionStatus.set(ServerConnectionStatus.CONNECTED)
				}
				HoloConnectionStopped.CRC -> {
					val packet = HoloConnectionStopped()
					packet.decode(NetBuffer.wrap(rawData))
					if (connectionStatus.get() != ServerConnectionStatus.DISCONNECTING) {
						connectionStatus.set(ServerConnectionStatus.DISCONNECTING)
						obj.sendBinary(HoloConnectionStopped(packet.reason).encode().array())
					}
					obj.close(WebSocketCloseReason.NORMAL.statusCode.toInt(), packet.reason.name)
				}
				else -> {
					handlePacket(rawData)
				}
			}
		}
	}
	
	enum class ServerConnectionStatus {
		CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED
	}
	
	companion object {
		
		private const val PROTOCOL_VERSION = "20220620-15:00"
		private const val CONNECT_TIMEOUT = 5000
		
		private fun createSocket(connectionUri: URI): Socket {
			if (connectionUri.scheme == "wss") {
				val sslContext = SSLContext.getInstance("TLSv1.3")
				val tm = null // To disable server verification, use: arrayOf<TrustManager>(TrustingTrustManager())
				sslContext.init(null, tm, SecureRandom())
				val sslSocket = sslContext.socketFactory.createSocket() as SSLSocket
				
				sslSocket.enabledProtocols = arrayOf("TLSv1.3")
				// Last Updated: 02 May 2021
				sslSocket.enabledCipherSuites = sslSocket.supportedCipherSuites
					// We want either AES256 GCM or CHACHA20, in the TLSv1.3 cipher format
					.filter {it.startsWith("TLS_AES_256_GCM") || it.startsWith("TLS_CHACHA20")}
					// SHA256 and SHA384 are both solid hashing algorithms
					.filter {it.endsWith("SHA256") || it.endsWith("SHA384")}
					// Prioritize CHACHA20, because it is stream-based rather than block-based
					.sortedBy { if (it.startsWith("TLS_CHACHA20")) 0 else 1 }
					.toTypedArray()
				Log.t("Using TLSv1.3 ciphers: %s", Arrays.toString(sslSocket.enabledCipherSuites))
				
				return sslSocket
			} else {
				return Socket()
			}
		}
		
	}
	
}