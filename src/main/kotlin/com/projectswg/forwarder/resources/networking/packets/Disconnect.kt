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

class Disconnect : Packet {
	
	var connectionId: Int = 0
		private set
	var reason: DisconnectReason = DisconnectReason.NONE
		private set
	
	constructor(connectionId: Int, reason: DisconnectReason) {
		this.connectionId = connectionId
		this.reason = reason
	}
	
	constructor(data: NetBuffer) {
		this.decode(data)
	}
	
	override fun decode(data: NetBuffer) {
		data.position(2)
		connectionId = data.netInt
		reason = getReason(data.netShort.toInt())
	}
	
	override fun encode(): NetBuffer {
		val data = NetBuffer.allocate(8)
		data.addNetShort(5)
		data.addNetInt(connectionId)
		data.addNetShort(reason.reason.toInt())
		return data
	}
	
	private fun getReason(reason: Int): DisconnectReason {
		for (dr in DisconnectReason.values())
			if (dr.reason.toInt() == reason)
				return dr
		return DisconnectReason.NONE
	}
	
	enum class DisconnectReason(reason: Int) {
		NONE(0x00),
		ICMP_ERROR(0x01),
		TIMEOUT(0x02),
		OTHER_SIDE_TERMINATED(0x03),
		MANAGER_DELETED(0x04),
		CONNECT_FAIL(0x05),
		APPLICATION(0x06),
		UNREACHABLE_CONNECTION(0x07),
		UNACKNOWLEDGED_TIMEOUT(0x08),
		NEW_CONNECTION_ATTEMPT(0x09),
		CONNECTION_REFUSED(0x0A),
		MUTUAL_CONNETION_ERROR(0x0B),
		CONNETING_TO_SELF(0x0C),
		RELIABLE_OVERFLOW(0x0D),
		COUNT(0x0E);
		
		val reason: Short = reason.toShort()
		
	}
	
	override fun toString(): String {
		return String.format("Disconnect[id=%d, reason=%s]", connectionId, reason)
	}
	
}
