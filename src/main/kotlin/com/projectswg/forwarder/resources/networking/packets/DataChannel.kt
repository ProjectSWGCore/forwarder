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

class DataChannel : Packet, SequencedPacket {
	
	private val content: MutableList<ByteArray>
	private var multiPacket: Short = 0
	
	override var sequence: Short = 0
	var channel: Channel
	
	val length: Int
		get() {
			var length = 6
			for (packet in content) {
				val addLength = packet.size
				length += 1 + addLength + if (addLength >= 0xFF) 2 else 0
			}
			return length
		}
	
	val packets: List<ByteArray>
		get() = content
	
	val packetCount: Int
		get() = content.size
	
	constructor() {
		this.content = ArrayList()
		this.channel = Channel.DATA_CHANNEL_A
		this.sequence = 0
		this.multiPacket = 0
	}
	
	constructor(content: List<ByteArray>) {
		this.content = ArrayList(content)
		this.channel = Channel.DATA_CHANNEL_A
		this.sequence = 0
		this.multiPacket = (if (content.isEmpty()) 0 else 0x19).toShort()
	}
	
	constructor(data: NetBuffer) : this() {
		decode(data)
	}
	
	constructor(packets: Array<ByteArray>) : this() {
		content.addAll(listOf(*packets))
	}
	
	constructor(packet: ByteArray) : this() {
		content.add(packet)
	}
	
	override fun decode(data: NetBuffer) {
		super.decode(data)
		when (opcode) {
			9 -> channel = Channel.DATA_CHANNEL_A
			10 -> channel = Channel.DATA_CHANNEL_B
			11 -> channel = Channel.DATA_CHANNEL_C
			12 -> channel = Channel.DATA_CHANNEL_D
			else -> return
		}
		data.position(2)
		sequence = data.netShort
		multiPacket = data.netShort
		if (multiPacket.toInt() == 0x19) {
			var length: Int
			while (data.remaining() > 1) {
				length = data.byte.toInt() and 0xFF
				if (length == 0xFF)
					length = data.netShort.toInt()
				if (length > data.remaining()) {
					data.position(data.position() - 1)
					return
				}
				content.add(data.getArray(length))
			}
		} else {
			data.seek(-2)
			content.add(data.getArray(data.remaining()))
		}
	}
	
	override fun encode(): NetBuffer {
		val data = NetBuffer.allocate(length)
		data.addNetShort(channel.opcode)
		data.addNetShort(sequence.toInt())
		data.addNetShort(0x19)
		for (pData in content) {
			if (pData.size >= 0xFF) {
				data.addByte(0xFF)
				data.addNetShort(pData.size)
			} else {
				data.addByte(pData.size)
			}
			data.addRawArray(pData)
		}
		return data
	}
	
	fun addPacket(packet: ByteArray) {
		content.add(packet)
	}
	
	fun clearPackets() {
		content.clear()
	}
	
	override fun equals(other: Any?): Boolean {
		return other is DataChannel && sequence == other.sequence
	}
	
	override fun hashCode(): Int {
		return sequence.toInt()
	}
	
	enum class Channel(val opcode: Int) {
		DATA_CHANNEL_A(9),
		DATA_CHANNEL_B(10),
		DATA_CHANNEL_C(11),
		DATA_CHANNEL_D(12)
	}
	
	override fun toString(): String {
		return String.format("DataChannel[seq=%d, packets=%d]", sequence, content.size)
	}
	
}
