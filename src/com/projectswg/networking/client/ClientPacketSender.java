package com.projectswg.networking.client;

import network.packets.swg.SWGPacket;

import com.projectswg.networking.Packet;

public interface ClientPacketSender {
	
	void sendPackaged(byte[] ... data);
	void sendPackaged(SWGPacket ... data);
	void sendRaw(byte[] ... data);
	void sendRaw(Packet ... p);
	
}
