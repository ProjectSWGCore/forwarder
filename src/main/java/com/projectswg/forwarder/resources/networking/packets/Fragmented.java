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
package com.projectswg.forwarder.resources.networking.packets;

import com.projectswg.common.network.NetBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Fragmented extends Packet implements SequencedPacket {
	
	private short sequence;
	private byte [] payload;
	
	public Fragmented() {
		this.sequence = 0;
		this.payload = null;
	}
	
	public Fragmented(ByteBuffer data) {
		decode(data);
	}
	
	public Fragmented(short sequence, byte [] payload) {
		this.sequence = sequence;
		this.payload = payload;
	}
	
	@Override
	public void decode(ByteBuffer bb) {
		super.decode(bb);
		NetBuffer data = NetBuffer.wrap(bb);
		short type = data.getNetShort();
		assert (type - 0x0D) < 4;
		
		this.sequence = data.getNetShort();
		this.payload = data.getArray(data.remaining());
	}
	
	@Override
	public ByteBuffer encode() {
		NetBuffer data = NetBuffer.allocate(4 + payload.length);
		data.addNetShort(0x0D);
		data.addNetShort(sequence);
		data.addRawArray(payload);
		return data.getBuffer();
	}
	
	@Override
	public boolean equals(Object o) {
		return o instanceof Fragmented && sequence == ((Fragmented) o).sequence;
	}
	
	@Override
	public int hashCode() {
		return sequence;
	}
	
	@Override
	public String toString() {
		return String.format("Fragmented[seq=%d, len=%d]", sequence, payload.length);
	}
	
	@Override
	public short getSequence() {
		return sequence;
	}
	
	public byte[] getPayload() {
		return payload;
	}
	
	@Override
	public void setSequence(short sequence) {
		this.sequence = sequence;
	}
	
	public void setPayload(byte[] payload) {
		this.payload = payload;
	}
	
	public static byte [][] split(byte [] data) {
		int offset = 0;
		int packetCount = (int) Math.ceil((data.length+4)/16377.0); // 489
		byte [][] packets = new byte[packetCount][];
		for (int i = 0; i < packetCount; i++) {
			int header = (i == 0) ? 4 : 0;
			ByteBuffer segment = ByteBuffer.allocate(Math.min(data.length-offset-header, 16377)).order(ByteOrder.BIG_ENDIAN);
			if (i == 0)
				segment.putInt(data.length);
			int segmentLength = segment.remaining();
			segment.put(data, offset, segmentLength);
			offset += segmentLength;
			packets[i] = segment.array();
		}
		return packets;
	}
	
}
