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
import java.util.*

class MultiPacket @JvmOverloads constructor(private val content: MutableList<ByteArray> = ArrayList()) : Packet() {
	
	val length: Int
		get() {
			var length = 2
			for (packet in content) {
				length += packet.size + 1
				if (packet.size >= 255)
					length += 2
			}
			return length
		}
	
	val packets: List<ByteArray>
		get() = content
	
	constructor(data: NetBuffer) : this(ArrayList<ByteArray>()) {
		decode(data)
	}
	
	override fun decode(data: NetBuffer) {
		data.position(2)
		var pLength = getNextPacketLength(data)
		while (data.remaining() >= pLength && pLength > 0) {
			content.add(data.getArray(pLength))
			pLength = getNextPacketLength(data)
		}
	}
	
	override fun encode(): NetBuffer {
		val data = NetBuffer.allocate(length)
		data.addNetShort(3)
		for (packet in content) {
			if (packet.size >= 255) {
				data.addByte(255)
				data.addShort(packet.size)
			} else {
				data.addByte(packet.size)
			}
			data.addRawArray(packet)
		}
		return data
	}
	
	fun addPacket(packet: ByteArray) {
		content.add(packet)
	}
	
	fun clearPackets() {
		content.clear()
	}
	
	private fun getNextPacketLength(data: NetBuffer): Int {
		if (data.remaining() < 1)
			return 0
		val length = data.byte.toInt() and 0xFF
		return if (length == 255) {
			if (data.remaining() < 2) 0 else data.short.toInt() and 0xFFFF
		} else length
	}
	
	override fun toString(): String {
		return String.format("MultiPacket[packets=%d]", content.size)
	}
	
}
