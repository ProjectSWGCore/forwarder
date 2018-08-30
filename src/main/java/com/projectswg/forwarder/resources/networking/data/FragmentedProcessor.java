package com.projectswg.forwarder.resources.networking.data;

import com.projectswg.common.network.NetBuffer;
import com.projectswg.forwarder.resources.networking.packets.Fragmented;

import java.util.ArrayList;
import java.util.List;

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
		
		NetBuffer payload = NetBuffer.wrap(frag.getPayload());
		int length = payload.getNetInt();
		
		if ((int) ((length+4.0) / 489) > fragmentedBuffer.size())
			return null; // Doesn't have the minimum number of required packets
		
		return processFragmentedReady(length);
	}
	
	private byte [] processFragmentedReady(int size) {
		byte [] combined = new byte[size];
		int index = 0;
		while (index < combined.length) {
			byte [] payload = fragmentedBuffer.remove(0).getPayload();
			int header = (index == 0) ? 4 : 0;
			
			System.arraycopy(payload, header, combined, index, payload.length - header);
			index += payload.length - header;
		}
		return combined;
	}
	
}
