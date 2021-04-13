/***********************************************************************************
 * Copyright (c) 2015 /// Project SWG /// www.projectswg.com                        *
 * *
 * ProjectSWG is the first NGE emulator for Star Wars Galaxies founded on           *
 * July 7th, 2011 after SOE announced the official shutdown of Star Wars Galaxies.  *
 * Our goal is to create an emulator which will provide a server for players to     *
 * continue playing a game similar to the one they used to play. We are basing      *
 * it on the final publish of the game prior to end-game events.                    *
 * *
 * This file is part of Holocore.                                                   *
 * *
 * -------------------------------------------------------------------------------- *
 * *
 * Holocore is free software: you can redistribute it and/or modify                 *
 * it under the terms of the GNU Affero General Public License as                   *
 * published by the Free Software Foundation, either version 3 of the               *
 * License, or (at your option) any later version.                                  *
 * *
 * Holocore is distributed in the hope that it will be useful,                      *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of                   *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                    *
 * GNU Affero General Public License for more details.                              *
 * *
 * You should have received a copy of the GNU Affero General Public License         *
 * along with Holocore.  If not, see <http:></http:>//www.gnu.org/licenses/>.                *
 * *
 */
package com.projectswg.forwarder.resources.networking.packets

import com.projectswg.common.network.NetBuffer


class ServerNetworkStatusUpdate : Packet {
	
	var clientTickCount: Short = 0
	var serverSyncStampLong = 0
	var clientPacketsSent: Long = 0
	var clientPacketsRecv: Long = 0
	var serverPacketsSent: Long = 0
	var serverPacketsRecv: Long = 0
	
	constructor()
	
	constructor(data: NetBuffer) {
		decode(data)
	}
	
	constructor(clientTickCount: Int, serverSyncStamp: Int, clientSent: Long, clientRecv: Long, serverSent: Long, serverRecv: Long) {
		this.clientTickCount = clientTickCount.toShort()
		this.serverSyncStampLong = serverSyncStamp
		this.clientPacketsSent = clientSent
		this.clientPacketsRecv = clientRecv
		this.serverPacketsSent = serverSent
		this.serverPacketsRecv = serverRecv
	}
	
	override fun decode(data: NetBuffer) {
		data.position(2)
		clientTickCount = data.netShort
		serverSyncStampLong = data.netInt
		clientPacketsSent = data.netLong
		clientPacketsRecv = data.netLong
		serverPacketsSent = data.netLong
		serverPacketsRecv = data.netLong
	}
	
	override fun encode(): NetBuffer {
		val data = NetBuffer.allocate(40)
		data.addNetShort(8)
		data.addNetShort(clientTickCount.toInt())
		data.addNetInt(serverSyncStampLong)
		data.addNetLong(clientPacketsSent)
		data.addNetLong(clientPacketsRecv)
		data.addNetLong(serverPacketsSent)
		data.addNetLong(serverPacketsRecv)
		return data
	}
	
	override fun toString(): String {
		return String.format("ServerNetworkStatusUpdate[ticks=%d, syncStamp=%d, clientSent=%d, clientRecv=%d, serverSent=%d, serverRecv=%d]", clientTickCount, serverSyncStampLong, clientPacketsSent, clientPacketsRecv, serverPacketsSent, serverPacketsRecv)
	}
	
}
