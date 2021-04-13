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

class ClientNetworkStatusUpdate : Packet {
	
	var tick: Int = 0
	var lastUpdate: Int = 0
	var averageUpdate: Int = 0
	var shortestUpdate: Int = 0
	var longestUpdate: Int = 0
	var lastServerUpdate: Int = 0
	var sent: Long = 0
	var recv: Long = 0
	
	constructor()
	constructor(data: NetBuffer) {
		decode(data)
	}
	
	constructor(clientTickCount: Int, lastUpdate: Int, avgUpdate: Int, shortUpdate: Int, longUpdate: Int, lastServerUpdate: Int, packetsSent: Long, packetsRecv: Long) {
		this.tick = clientTickCount
		this.lastUpdate = lastUpdate
		this.averageUpdate = avgUpdate
		this.shortestUpdate = shortUpdate
		this.longestUpdate = longUpdate
		this.lastServerUpdate = lastServerUpdate
		this.sent = packetsSent
		this.recv = packetsRecv
	}
	
	override fun decode(data: NetBuffer) {
		data.netShort // 0x07
		tick = data.netShort.toInt()
		lastUpdate = data.netInt
		averageUpdate = data.netInt
		shortestUpdate = data.netInt
		longestUpdate = data.netInt
		lastServerUpdate = data.netInt
		sent = data.netLong
		recv = data.netLong
	}
	
	override fun encode(): NetBuffer {
		val data = NetBuffer.allocate(40)
		data.addNetShort(7)
		data.addNetShort(tick)
		data.addNetInt(lastUpdate)
		data.addNetInt(averageUpdate)
		data.addNetInt(shortestUpdate)
		data.addNetInt(longestUpdate)
		data.addNetInt(lastServerUpdate)
		data.addNetLong(sent)
		data.addNetLong(recv)
		return data
	}
	
	override fun toString(): String {
		return String.format("ClientNetworkStatusUpdate[tick=%d, lastUpdate=%d, avgUpdate=%d, shortestUpdate=%d, longestUpdate=%d, lastServerUpdate=%d, sent=%d, recv=%d]", tick, lastUpdate, averageUpdate, shortestUpdate, longestUpdate, lastServerUpdate, sent, recv)
	}
	
}
