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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min

class Fragmented : Packet, SequencedPacket {
	
	override var sequence: Short = 0
	lateinit var payload: ByteArray
	
	constructor(data: NetBuffer) {
		decode(data)
	}
	
	constructor(sequence: Short, payload: ByteArray) {
		this.sequence = sequence
		this.payload = payload
	}
	
	override fun decode(data: NetBuffer) {
		super.decode(data)
		val type = data.netShort
		assert(type - 0x0D < 4)
		
		this.sequence = data.netShort
		this.payload = data.getArray(data.remaining())
	}
	
	override fun encode(): NetBuffer {
		val data = NetBuffer.allocate(4 + payload.size)
		data.addNetShort(0x0D)
		data.addNetShort(sequence.toInt())
		data.addRawArray(payload)
		return data
	}
	
	override fun equals(other: Any?): Boolean {
		return other is Fragmented && sequence == other.sequence
	}
	
	override fun hashCode(): Int {
		return sequence.toInt()
	}
	
	override fun toString(): String {
		return String.format("Fragmented[seq=%d, len=%d]", sequence, payload.size)
	}
	
	companion object {
		
		fun split(data: ByteArray): Array<ByteArray> {
			var offset = 0
			val packetCount = ceil((data.size + 4) / 16377.0).toInt() // 489
			val packets = ArrayList<ByteArray>(packetCount)
			for (i in 0 until packetCount) {
				val header = if (i == 0) 4 else 0
				val segment = ByteBuffer.allocate(min(data.size - offset - header, 16377)).order(ByteOrder.BIG_ENDIAN)
				if (i == 0)
					segment.putInt(data.size)
				val segmentLength = segment.remaining()
				segment.put(data, offset, segmentLength)
				offset += segmentLength
				packets[i] = segment.array()
			}
			return packets.toTypedArray()
		}
	}
	
}
