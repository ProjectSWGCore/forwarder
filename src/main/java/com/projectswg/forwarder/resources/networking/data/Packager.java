package com.projectswg.forwarder.resources.networking.data;

import com.projectswg.forwarder.resources.networking.packets.DataChannel;
import com.projectswg.forwarder.resources.networking.packets.Fragmented;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

public class Packager {
	
	private final AtomicInteger size;
	private final DataChannel channel;
	private final Queue<byte[]> outboundRaw;
	private final Queue<SequencedOutbound> outboundPackaged;
	private final ProtocolStack stack;
	
	public Packager(Queue<byte[]> outboundRaw, Queue<SequencedOutbound> outboundPackaged, ProtocolStack stack) {
		this.size = new AtomicInteger(8);
		this.channel = new DataChannel();
		this.outboundRaw = outboundRaw;
		this.outboundPackaged = outboundPackaged;
		this.stack = stack;
	}
	
	public void handle(int maxPackaged) {
		byte [] packet;
		int packetSize;
		
		while (!outboundRaw.isEmpty() && outboundPackaged.size() < maxPackaged) {
			packet = outboundRaw.poll();
			if (packet == null)
				break;
			packetSize = getPacketLength(packet);
			
			if (size.get() + packetSize >= 496) // overflowed previous packet
				sendDataChannel();
			
			if (packetSize < 496) {
				addToDataChannel(packet, packetSize);
			} else {
				sendFragmented(packet);
			}
		}
		sendDataChannel();
	}
	
	private void addToDataChannel(byte [] packet, int packetSize) {
		channel.addPacket(packet);
		size.getAndAdd(packetSize);
	}
	
	private void sendDataChannel() {
		if (channel.getPacketCount() == 0)
			return;
		
		channel.setSequence(stack.getAndIncrementTxSequence());
		outboundPackaged.add(new SequencedOutbound(channel.getSequence(), channel.encode().array()));
		reset();
	}
	
	private void sendFragmented(byte [] packet) {
		Fragmented[] frags = Fragmented.encode(ByteBuffer.wrap(packet), stack.getTxSequence());
		stack.getAndIncrementTxSequence(frags.length);
		for (Fragmented frag : frags) {
			outboundPackaged.add(new SequencedOutbound(frag.getSequence(), frag.encode().array()));
		}
	}
	
	private void reset() {
		channel.clearPackets();
		size.set(8);
	}
	
	private static int getPacketLength(byte [] data) {
		int len = data.length;
		if (len >= 255)
			return len + 3;
		return len + 1;
	}
	
}
