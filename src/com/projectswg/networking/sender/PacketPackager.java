package com.projectswg.networking.sender;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.Packet;
import com.projectswg.networking.soe.DataChannelA;
import com.projectswg.networking.soe.Fragmented;
import com.projectswg.utilities.ThreadUtilities;

public class PacketPackager {
	
	private final NetInterceptor interceptor;
	private final AtomicBoolean running;
	private final Queue<byte []> inboundQueue;
	private final PacketSender sender;
	private ExecutorService executor;
	private short txSequence;
	
	public PacketPackager(NetInterceptor interceptor, PacketSender sender) {
		this.interceptor = interceptor;
		this.sender = sender;
		this.running = new AtomicBoolean(false);
		this.inboundQueue = new LinkedList<>();
		this.executor = null;
		this.txSequence = 0;
	}
	
	public void start() {
		if (running.getAndSet(true))
			return;
		executor = Executors.newSingleThreadExecutor(ThreadUtilities.newThreadFactory("packet-packager"));
		executor.execute(() -> preprocessRunnable());
	}
	
	public void stop() {
		if (!running.getAndSet(false))
			return;
		executor.shutdownNow();
		try {
			executor.awaitTermination(1, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			
		}
	}
	
	public void reset() {
		synchronized (inboundQueue) {
			inboundQueue.clear();
		}
		txSequence = 0;
	}
	
	public void addToPackage(byte [] data) {
		synchronized (inboundQueue) {
			inboundQueue.add(data);
			inboundQueue.notifyAll();
		}
	}
	
	public short getSequence() {
		return txSequence;
	}
	
	private void preprocessRunnable() {
		Queue<byte []> outbound = new ArrayBlockingQueue<>(8, true);
		boolean lastWasEmpty = false;
		int lastChanceIterations = 3;
		while (running.get() || lastChanceIterations > 0) {
			synchronized (inboundQueue) {
				if (inboundQueue.isEmpty() && lastWasEmpty && running.get()) {
					try { inboundQueue.wait(); } catch (InterruptedException e) { }
				}
				lastWasEmpty = inboundQueue.isEmpty();
				preprocessPackage(outbound);
			}
			try {
				Thread.sleep(running.get() ? 25 : 5);
			} catch (InterruptedException e) {
				
			}
			if (!running.get())
				lastChanceIterations--;
		}
	}
	
	private void preprocessPackage(Queue<byte []> outbound) {
		byte [] data;
		int size = 8;
		while (!inboundQueue.isEmpty()) {
			data = interceptor.interceptServer(inboundQueue.poll());
			if (size + getPacketLength(data) >= 496 && !outbound.isEmpty()) {
				createOutboundPacket(outbound, size);
				size = 8;
			}
			outbound.add(data);
			size += getPacketLength(data);
			if (size >= 496 || outbound.size() == 8 || inboundQueue.isEmpty()) {
				createOutboundPacket(outbound, size);
				size = 8;
			}
		}
	}
	
	private int getPacketLength(byte [] data) {
		if (data.length >= 255)
			return data.length + 3;
		else
			return data.length + 1;
	}
	
	private void createOutboundPacket(Queue<byte []> outbound, int size) {
		if (size <= 496) { // Fits into single data packet
			DataChannelA channel = new DataChannelA();
			channel.setSequence(txSequence++);
			while (!outbound.isEmpty())
				channel.addPacket(outbound.poll());
			sender.send(channel);
		} else { // Fragmented
			while (!outbound.isEmpty()) {
				Fragmented [] frags = Fragmented.encode(ByteBuffer.wrap(outbound.poll()), txSequence);
				txSequence += frags.length;
				for (Fragmented frag : frags) {
					sender.send(frag);
				}
			}
		}
	}
	
	public interface PacketSender {
		void send(Packet packet);
	}
	
}
