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
import java.nio.ByteOrder;

import com.projectswg.networking.Packet;


public class SessionRequest extends Packet {
	
	private int crcLength;
	private int connectionId;
	private int udpSize;
	
	public SessionRequest() {
		
	}
	
	public SessionRequest(ByteBuffer data) {
		decode(data);
	}
	
	public SessionRequest(int crcLength, int connectionId, int udpSize) {
		this.crcLength    = crcLength;
		this.connectionId = connectionId;
		this.udpSize      = udpSize;
	}
	
	public void decode(ByteBuffer packet) {
		super.decode(packet);
		packet.position(2);
		crcLength    = getNetInt(packet);
		connectionId = getNetInt(packet);
		udpSize      = getNetInt(packet);
	}
	
	public ByteBuffer encode() {
		ByteBuffer bb = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN);
		addNetShort(bb, 1);
		addNetInt(bb, crcLength);
		addNetInt(bb, connectionId);
		addNetInt(bb, udpSize);
		return bb;
	}
	
	public int getCrcLength()    { return crcLength; }
	public int getConnectionId() { return connectionId; }
	public int getUdpSize()      { return udpSize; }
}
