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


public class DataChannelA extends Packet implements SequencedPacket {
	
	private List <byte []> content = new ArrayList<byte []>();
	private short sequence = 0;
	private short multiPacket = 0;
	
	public DataChannelA() {
		
	}
	
	public DataChannelA(ByteBuffer data) {
		decode(data);
	}
	
	public DataChannelA(byte [][] packets) {
		for (byte [] p : packets) {
			content.add(p);
		}
	}
	
	public void decode(ByteBuffer data) {
		super.decode(data);
		if (getOpcode() != 9)
			return;
		data.position(2);
		sequence = getNetShort(data);
		multiPacket = getNetShort(data);
		if (multiPacket == 0x19) {
			int length = 0;
			while (data.remaining() > 1) {
				length = getByte(data) & 0xFF;
				if (length == 0xFF)
					length = getNetShort(data);
				if (length > data.remaining()) {
					data.position(data.position()-1);
					return;
				}
				byte [] pData = new byte[length];
				data.get(pData);
				content.add(pData);
			}
		} else {
			data.position(data.position()-2);
			byte [] pData = new byte[data.remaining()];
			data.get(pData);
			content.add(pData);
		}
	}
	
	public ByteBuffer encode() {
		return encode(this.sequence);
	}
	
	public ByteBuffer encode(int sequence) {
		this.sequence = (short) sequence;
		if (content.size() == 1) {
			byte [] pData = content.get(0);
			ByteBuffer data = ByteBuffer.allocate(4 + pData.length);
			addNetShort(data, 9);
			addNetShort(data, sequence);
			data.put(pData);
			return data;
		} else if (content.size() > 1) {
			int length = getLength();
			ByteBuffer data= ByteBuffer.allocate(length);
			addNetShort(data, 9);
			addNetShort(data, sequence);
			addNetShort(data, 0x19);
			for (byte [] pData : content) {
				if (pData.length >= 0xFF) {
					addByte(data, 0xFF);
					addNetShort(data, pData.length);
				} else {
					data.put((byte) pData.length);
				}
				data.put(pData);
			}
			return data;
		} else {
			return ByteBuffer.allocate(0);
		}
	}
	
	public void addPacket(byte [] packet) {
		content.add(packet);
	}
	
	public void clearPackets() {
		content.clear();
	}
	
	public int getLength() {
		if (content.size() == 1) {
			return 4 + content.get(0).length;
		} else {
			int length = 6;
			for (byte [] packet : content) {
				int addLength = packet.length;
				length += 1 + addLength + ((addLength >= 0xFF) ? 2 : 0);
			}
			return length;
		}
	}
	
	@Override
	public int compareTo(SequencedPacket p) {
		if (sequence < p.getSequence())
			return -1;
		if (sequence == p.getSequence())
			return 0;
		return 1;
	}
	
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof DataChannelA))
			return false;
		return ((DataChannelA) o).sequence == sequence;
	}
	
	@Override
	public int hashCode() {
		return sequence;
	}

	public void setSequence(short sequence) { this.sequence = sequence; }
	
	public short getSequence() { return sequence; }
	public List <byte []> getPackets() { return content; }
}
