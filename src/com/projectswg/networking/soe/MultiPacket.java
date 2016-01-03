/***********************************************************************************
* Copyright (c) 2015 /// Project SWG /// www.projectswg.com                        *
*                                                                                  *
* ProjectSWG is the first NGE emulator for Star Wars Galaxies founded on           *
* July 7th, 2011 after SOE announced the official shutdown of Star Wars Galaxies.  *
* Our goal is to create an emulator which will provide a server for players to     *
* continue playing a game similar to the one they used to play. We are basing      *
* it on the final publish of the game prior to end-game events.                    *
*                                                                                  *
* This file is part of Holocore.                                                   *
*                                                                                  *
* -------------------------------------------------------------------------------- *
*                                                                                  *
* Holocore is free software: you can redistribute it and/or modify                 *
* it under the terms of the GNU Affero General Public License as                   *
* published by the Free Software Foundation, either version 3 of the               *
* License, or (at your option) any later version.                                  *
*                                                                                  *
* Holocore is distributed in the hope that it will be useful,                      *
* but WITHOUT ANY WARRANTY; without even the implied warranty of                   *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                    *
* GNU Affero General Public License for more details.                              *
*                                                                                  *
* You should have received a copy of the GNU Affero General Public License         *
* along with Holocore.  If not, see <http://www.gnu.org/licenses/>.                *
*                                                                                  *
***********************************************************************************/
package com.projectswg.networking.soe;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.projectswg.networking.Packet;


public class MultiPacket extends Packet {
	
	private final List <byte []> content;
	
	public MultiPacket() {
		this(new ArrayList<>());
	}
	
	public MultiPacket(ByteBuffer data) {
		this(new ArrayList<>());
		decode(data);
	}
	
	public MultiPacket(List <byte []> packets) {
		this.content = packets;
	}
	
	public void decode(ByteBuffer data) {
		data.position(2);
		int pLength = getNextPacketLength(data);
		while (data.remaining() >= pLength && pLength > 0) {
			byte [] pData = new byte[pLength];
			data.get(pData);
			content.add(pData);
			pLength = getNextPacketLength(data);
		}
	}
	
	public ByteBuffer encode() {
		int length = getLength();
		ByteBuffer data = ByteBuffer.allocate(length);
		addNetShort(data, 3);
		for (byte [] packet : content) {
			if (packet.length >= 255) {
				addByte(data, 255);
				addShort(data, packet.length);
			} else {
				addByte(data, packet.length);
			}
			data.put(packet);
		}
		return data;
	}
	
	public int getLength() {
		int length = 2;
		for (byte [] packet : content) {
			length += packet.length + 1;
			if (packet.length >= 255)
				length += 2;
		}
		return length;
	}
	
	public void addPacket(byte [] packet) {
		content.add(packet);
	}
	
	public void clearPackets() {
		content.clear();
	}
	
	public List <byte []> getPackets() {
		return content;
	}
	
	private int getNextPacketLength(ByteBuffer data) {
		if (data.remaining() < 1)
			return 0;
		int length = getByte(data) & 0xFF;
		if (length == 255) {
			if (data.remaining() < 2)
				return 0;
			return getShort(data) & 0xFFFF;
		}
		return length;
	}
	
}
