package com.projectswg.forwarder.resources.networking

import com.projectswg.common.network.NetBuffer
import com.projectswg.common.network.packets.PacketType
import com.projectswg.common.network.packets.swg.login.LoginClusterStatus
import com.projectswg.forwarder.Forwarder.ForwarderData
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NetInterceptor(private val data: ForwarderData) {
	
	fun interceptServer(data: ByteArray): ByteArray {
		if (data.size < 6)
			return data
		val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
		when (PacketType.fromCrc(bb.getInt(2))) {
			PacketType.LOGIN_CLUSTER_STATUS -> return getServerList(NetBuffer.wrap(bb))
			else -> return data
		}
	}
	
	private fun getServerList(data: NetBuffer): ByteArray {
		val cluster = LoginClusterStatus()
		cluster.decode(data)
		for (g in cluster.galaxies) {
			g.address = "127.0.0.1"
			g.zonePort = this.data.zonePort
			g.pingPort = this.data.pingPort
		}
		return cluster.encode().array()
	}
	
}
