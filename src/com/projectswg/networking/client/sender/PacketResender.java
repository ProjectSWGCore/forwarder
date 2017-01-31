package com.projectswg.networking.client.sender;

import java.util.LinkedList;
import java.util.Queue;
import com.projectswg.concurrency.PswgBasicScheduledThread;
import com.projectswg.networking.client.ClientPacketSender;
import com.projectswg.networking.soe.SequencedPacket;

/**
 * This class is in charge of resending any unacknowledged packets
 */
public class PacketResender {
	
	private static final long RESEND_DELAY = 25;
	
	private Queue<SequencedOutbound> sentPackets;
	private final ClientPacketSender sender;
	private PswgBasicScheduledThread resender;
	private SenderMode mode;
	
	public PacketResender(ClientPacketSender sender) {
		this.sender = sender;
		this.sentPackets = new LinkedList<>();
		this.resender = new PswgBasicScheduledThread("packet-resender", () -> resendRunnable());
		this.mode = SenderMode.FASTEST;
	}
	
	public void start() {
		resender.startWithFixedDelay(0, RESEND_DELAY);
	}
	
	public void stop() {
		resender.stop();
		resender.awaitTermination(1000);
		synchronized (sentPackets) {
			sentPackets.clear();
		}
	}
	
	public void restart() {
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
			sender.sendRaw(data);
			out.updateTimeSinceSent();
		}
	}
	
	public void resendTo(short sequence) {
		synchronized (sentPackets) {
			for (SequencedOutbound packet : sentPackets) {
				if (packet.getSequence() <= sequence) {
					if (packet.getTimeSinceSent() >= 100) {
						sender.sendRaw(packet.getData());
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
					sender.sendRaw(packet.getData());
					packet.updateTimeSinceSent();
				}
				if (++count >= mode.getMax())
					break;
			}
		}
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
