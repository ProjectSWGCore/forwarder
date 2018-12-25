package com.projectswg.forwarder.resources.networking.data;

import com.projectswg.forwarder.resources.networking.data.ProtocolStack.ConnectionStream;
import com.projectswg.forwarder.resources.networking.packets.DataChannel;
import com.projectswg.forwarder.resources.networking.packets.Fragmented;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class Packager {
	
	private final AtomicInteger size;
	private final List<byte[]> dataChannel;
	private final BlockingQueue<byte[]> outboundRaw;
	private final ConnectionStream<SequencedOutbound> outboundPackaged;
	
	public Packager(BlockingQueue<byte[]> outboundRaw, ConnectionStream<SequencedOutbound> outboundPackaged, ProtocolStack stack) {
		this.size = new AtomicInteger(8);
		this.dataChannel = new ArrayList<>();
		this.outboundRaw = outboundRaw;
		this.outboundPackaged = outboundPackaged;
	}
	
	public void handle(int maxPackaged) {
		byte [] packet;
		int packetSize;
		
		while (outboundPackaged.size() < maxPackaged) {
			packet = outboundRaw.poll();
			if (packet == null)
				break;
			
			packetSize = getPacketLength(packet);

			if (size.get() + packetSize >= 16384) // max data channel size
				sendDataChannel();

			if (packetSize < 16384) { // if overflowed, must go into fragmented
				addToDataChannel(packet, packetSize);
			} else {
				sendFragmented(packet);
			}
		}
		sendDataChannel();
	}
	
	private void addToDataChannel(byte [] packet, int packetSize) {
		dataChannel.add(packet);
		size.getAndAdd(packetSize);
	}
	
	private void sendDataChannel() {
		if (dataChannel.isEmpty())
			return;
		
		outboundPackaged.addOrdered(new SequencedOutbound(new DataChannel(dataChannel)));
		reset();
	}
	
	private void sendFragmented(byte [] packet) {
		byte[][] frags = Fragmented.split(packet);
		for (byte [] frag : frags) {
			outboundPackaged.addOrdered(new SequencedOutbound(new Fragmented((short) 0, frag)));
		}
	}
	
	private void reset() {
		dataChannel.clear();
		size.set(8);
	}
	
	private static int getPacketLength(byte [] data) {
		int len = data.length;
		if (len >= 255)
			return len + 3;
		return len + 1;
	}
	
}
