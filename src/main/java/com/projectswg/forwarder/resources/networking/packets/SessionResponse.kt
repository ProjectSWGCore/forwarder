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

class SessionResponse : Packet {
	
	var connectionId: Int = 0
	var crcSeed: Int = 0
	var crcLength: Byte = 0
	var encryptionFlag: Byte = 0
	var xorLength: Byte = 0
	var udpSize: Int = 0
	
	constructor()
	
	constructor(data: NetBuffer) {
		decode(data)
	}
	
	constructor(connectionId: Int, crcSeed: Int, crcLength: Byte, encryptionFlag: Byte, xorLength: Byte, udpSize: Int) {
		this.connectionId = connectionId
		this.crcSeed = crcSeed
		this.crcLength = crcLength
		this.encryptionFlag = encryptionFlag
		this.xorLength = xorLength
		this.udpSize = udpSize
	}
	
	override fun decode(data: NetBuffer) {
		super.decode(data)
		data.position(2)
		connectionId = data.netInt
		crcSeed = data.netInt
		crcLength = data.byte
		encryptionFlag = data.byte
		xorLength = data.byte
		udpSize = data.netInt
	}
	
	override fun encode(): NetBuffer {
		val data = NetBuffer.allocate(17)
		data.addNetShort(2)
		data.addNetInt(connectionId)
		data.addNetInt(crcSeed)
		data.addByte(crcLength.toInt())
		data.addByte(encryptionFlag.toInt())
		data.addByte(xorLength.toInt())
		data.addNetInt(udpSize)
		return data
	}
	
	override fun toString(): String {
		return String.format("SessionResponse[connectionId=%d, crcSeed=%d, crcLength=%d, encryptionFlag=%d, xorLength=%d, udpSize=%d]", connectionId, crcSeed, crcLength, encryptionFlag, xorLength, udpSize)
	}
	
}
