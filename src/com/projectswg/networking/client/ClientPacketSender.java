package com.projectswg.networking.client;

import network.packets.swg.SWGPacket;

import com.projectswg.networking.Packet;

public interface ClientPacketSender {
	
	/** Adds the specified packets to a buffer to guarantee sending in-order */
	void sendPackaged(byte[] ... packets);
	/** Adds the specified packets to a buffer to guarantee sending in-order */
	void sendPackaged(SWGPacket ... packets);
	/** Sends the specified packets via UDP immediately */
	void sendRaw(byte[] ... data);
	/** Sends the specified packets via UDP immediately */
	void sendRaw(Packet ... p);
	
}
