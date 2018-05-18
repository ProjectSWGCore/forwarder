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
import java.util.ArrayList;
import java.util.List;

public class DataChannel extends Packet implements SequencedPacket {
	
	private final List<byte[]> content;
	
	private Channel channel;
	private short sequence;
	private short multiPacket;
	
	public DataChannel() {
		this.content = new ArrayList<>();
		this.channel = Channel.DATA_CHANNEL_A;
		this.sequence = 0;
		this.multiPacket = 0;
	}
	
	public DataChannel(ByteBuffer data) {
		this();
		decode(data);
	}
	
	public DataChannel(byte[][] packets) {
		this();
		for (byte[] p : packets) {
			content.add(p);
		}
	}
	
	@Override
	public void decode(ByteBuffer data) {
		super.decode(data);
		switch (getOpcode()) {
			case 9:  channel = Channel.DATA_CHANNEL_A; break;
			case 10: channel = Channel.DATA_CHANNEL_B; break;
			case 11: channel = Channel.DATA_CHANNEL_C; break;
			case 12: channel = Channel.DATA_CHANNEL_D; break;
			default: return;
		}
		data.position(2);
		sequence = getNetShort(data);
		multiPacket = getNetShort(data);
		if (multiPacket == 0x19) {
			int length = 0;
			while (data.remaining() > 1) {
				length = data.get() & 0xFF;
				if (length == 0xFF)
					length = getNetShort(data);
				if (length > data.remaining()) {
					data.position(data.position() - 1);
					return;
				}
				byte[] pData = new byte[length];
				data.get(pData);
				content.add(pData);
			}
		} else {
			data.position(data.position() - 2);
			byte[] pData = new byte[data.remaining()];
			data.get(pData);
			content.add(pData);
		}
	}
	
	@Override
	public ByteBuffer encode() {
		return encode(this.sequence);
	}
	
	public ByteBuffer encode(int sequence) {
		this.sequence = (short) sequence;
		NetBuffer data;
		if (content.size() == 1) {
			byte[] pData = content.get(0);
			data = NetBuffer.allocate(4 + pData.length);
			data.addNetShort(channel.getOpcode());
			data.addNetShort(sequence);
			data.addRawArray(pData);
		} else if (content.size() > 1) {
			data = NetBuffer.allocate(getLength());
			data.addNetShort(channel.getOpcode());
			data.addNetShort(sequence);
			data.addNetShort(0x19);
			for (byte[] pData : content) {
				if (pData.length >= 0xFF) {
					data.addByte(0xFF);
					data.addNetShort(pData.length);
				} else {
					data.addByte(pData.length);
				}
				data.addRawArray(pData);
			}
		} else {
			data = NetBuffer.allocate(0);
		}
		return data.getBuffer();
	}
	
	public void addPacket(byte[] packet) {
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
			for (byte[] packet : content) {
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
		if (!(o instanceof DataChannel))
			return false;
		return ((DataChannel) o).sequence == sequence;
	}
	
	@Override
	public int hashCode() {
		return sequence;
	}
	
	public void setSequence(short sequence) {
		this.sequence = sequence;
	}
	
	public void setChannel(Channel channel) {
		this.channel = channel;
	}
	
	@Override
	public short getSequence() {
		return sequence;
	}
	
	public Channel getChannel() {
		return channel;
	}
	
	public List<byte[]> getPackets() {
		return content;
	}
	
	public int getPacketCount() {
		return content.size();
	}
	
	public enum Channel {
		DATA_CHANNEL_A(9),
		DATA_CHANNEL_B(10),
		DATA_CHANNEL_C(11),
		DATA_CHANNEL_D(12);
		
		private final int opcode;
		
		Channel(int opcode) {
			this.opcode = opcode;
		}
		
		public int getOpcode() {
			return opcode;
		}
	}
	
}
