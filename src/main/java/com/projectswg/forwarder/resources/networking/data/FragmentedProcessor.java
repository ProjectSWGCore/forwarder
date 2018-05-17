package com.projectswg.forwarder.resources.networking.data;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.projectswg.forwarder.resources.networking.packets.Fragmented;
import com.projectswg.forwarder.resources.networking.packets.Packet;

public class FragmentedProcessor {
	
	private final List<Fragmented> fragmentedBuffer;
	
	public FragmentedProcessor() {
		this.fragmentedBuffer = new ArrayList<>();
	}
	
	public void reset() {
		fragmentedBuffer.clear();
	}
	
	public byte [] addFragmented(Fragmented frag) {
		fragmentedBuffer.add(frag);
		frag = fragmentedBuffer.get(0);
		ByteBuffer data = frag.getPacketData();
		data.position(4);
		int size = Packet.getNetInt(data);
		int index = data.remaining();
		for (int i = 1; i < fragmentedBuffer.size() && index < size; i++)
			index += fragmentedBuffer.get(i).getPacketData().limit()-4;
		if (index == size)
			return processFragmentedReady(size);
		return null;
	}
	
	private byte [] processFragmentedReady(int size) {
		byte [] combined = new byte[size];
		int index = 0;
		while (index < combined.length) {
			ByteBuffer packet = fragmentedBuffer.get(0).getPacketData();
			packet.position(index == 0 ? 8 : 4);
			int len = packet.remaining();
			packet.get(combined, index, len);
			index += len;
			fragmentedBuffer.remove(0);
		}
		return combined;
	}
	
}
