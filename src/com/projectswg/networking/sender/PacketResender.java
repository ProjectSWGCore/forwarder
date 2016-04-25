package com.projectswg.networking.sender;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectswg.networking.soe.SequencedPacket;
import com.projectswg.utilities.ThreadUtilities;

public class PacketResender {
	
	private static final long RESEND_DELAY = 25;
	
	private final AtomicBoolean running;
	private Queue<SequencedOutbound> sentPackets;
	private final PacketSender sender;
	private ScheduledExecutorService executor;
	private SenderMode mode;
	
	public PacketResender(PacketSender sender) {
		this.sender = sender;
		this.running = new AtomicBoolean(false);
		this.sentPackets = new LinkedList<>();
		this.executor = null;
		this.mode = SenderMode.FAST;
	}
	
	public void start() {
		if (running.getAndSet(true))
			return;
		executor = Executors.newSingleThreadScheduledExecutor(ThreadUtilities.newThreadFactory("packet-resender"));
		executor.execute(() -> resendRunnable());
		executor.scheduleWithFixedDelay(()->resendRunnable(), RESEND_DELAY, RESEND_DELAY, TimeUnit.MILLISECONDS);
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
		synchronized (sentPackets) {
			sentPackets.clear();
		}
	}
	
	public void add(short sequence, byte [] data) {
		SequencedOutbound out = new SequencedOutbound(sequence, data);
		synchronized (sentPackets) {
			sentPackets.add(out);
		}
		if (mode != SenderMode.DEBUG) {
			sender.send(data);
			out.updateTimeSinceSent();
		}
	}
	
	public void resendTo(short sequence) {
		synchronized (sentPackets) {
			for (SequencedOutbound packet : sentPackets) {
				if (packet.getSequence() <= sequence) {
					if (packet.getTimeSinceSent() >= 100) {
						sender.send(packet.getData());
						packet.updateTimeSinceSent();
					}
				} else
					break;
			}
		}
	}
	
	public void clearTo(short sequence) {
		synchronized (sentPackets) {
			while (!sentPackets.isEmpty()) {
				if (sentPackets.peek().getSequence() <= sequence)
					sentPackets.remove();
				else if (sequence < 100 && sentPackets.peek().getSequence() > 100) // Wrap
					sentPackets.remove();
				else
					break;
			}
		}
	}
	
	private void resendRunnable() {
		int count = 0;
		synchronized (sentPackets) {
			for (SequencedOutbound packet : sentPackets) {
				if (packet.getTimeSinceSent() >= mode.getAcknowledgeTime()) {
					sender.send(packet.getData());
					packet.updateTimeSinceSent();
				}
				if (++count >= mode.getMax())
					break;
			}
		}
	}
	
	public interface PacketSender {
		void send(byte [] data);
	}
	
	private static class SequencedOutbound implements SequencedPacket {
		
		private short sequence;
		private long sent;
		private byte [] data;
		
		public SequencedOutbound(short sequence, byte [] data) {
			this.sequence = sequence;
			this.sent = 0;
			this.data = data;
		}
		
		public short getSequence() { return sequence; }
		public double getTimeSinceSent() { return (System.nanoTime()-sent)/1E6; }
		public byte [] getData() { return data; }
		
		public void updateTimeSinceSent() {
			sent = System.nanoTime();
		}
		
		public int compareTo(SequencedPacket p) {
			if (sequence < p.getSequence())
				return -1;
			if (sequence == p.getSequence())
				return 0;
			return 1;
		}
		
		public boolean equals(Object o) {
			if (!(o instanceof SequencedOutbound))
				return super.equals(o);
			return ((SequencedOutbound) o).getSequence() == sequence;
		}
		
		public int hashCode() {
			return sequence;
		}
	}
	
	private static enum SenderMode {
		/** Always sends one packet at a time and waits for an acknowledgement */
		DEBUG		(2000, 1),
		/** Re-sends packets if they aren't acknowledged within 2 seconds. Sends max of 1 at a time */
		SLOWEST		(2000, 1),
		/** Re-sends packets if they aren't acknowledged within 2 seconds. Sends max of 10 at a time */
		SLOW		(2000, 5),
		/** Re-sends packets if they aren't acknowledged within 2 seconds. Sends max of 10 at a time */
		FAST		(2000, 10),
		/** Re-sends packets if they aren't acknowledged within 2 seconds */
		FASTEST		(2000, Integer.MAX_VALUE);
		
		private int ackTime;
		private int max;
		
		SenderMode(int ackTime, int max) {
			this.ackTime = ackTime;
			this.max = max;
		}
		
		public int getAcknowledgeTime() {
			return ackTime;
		}
		
		public int getMax() {
			return max;
		}
	}
	
}
