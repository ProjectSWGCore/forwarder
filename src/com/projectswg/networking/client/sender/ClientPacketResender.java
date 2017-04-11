package com.projectswg.networking.client.sender;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.projectswg.common.concurrency.Delay;
import com.projectswg.common.concurrency.PswgBasicThread;
import com.projectswg.common.control.IntentManager;
import com.projectswg.common.debug.Log;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ClientSonyPacketIntent;
import com.projectswg.networking.Packet;
import com.projectswg.networking.client.ClientPacketSender;
import com.projectswg.networking.soe.Acknowledge;
import com.projectswg.networking.soe.OutOfOrder;
import com.projectswg.networking.soe.SequencedPacket;
import com.projectswg.resources.ClientConnectionStatus;

/**
 * This class is in charge of resending any unacknowledged packets
 */
public class ClientPacketResender {
	
	private final List<SequencedOutbound> sentPackets;
	private final ClientPacketSender sender;
	private final PswgBasicThread resender;
	private final CongestionAvoidance congAvoidance;
	private final AtomicLong lastSent;
	
	public ClientPacketResender(ClientPacketSender sender) {
		this.sender = sender;
		this.sentPackets = new ArrayList<>(128);
		this.resender = new PswgBasicThread("packet-resender", () -> resendRunnable());
		this.resender.setInterruptOnStop(true);
		this.congAvoidance = new CongestionAvoidance();
		this.lastSent = new AtomicLong(0);
	}
	
	public void start(IntentManager intentManager) {
		resender.start();
		intentManager.registerForIntent(ClientSonyPacketIntent.class, cspi -> handleClientPacket(cspi.getPacket()));
		intentManager.registerForIntent(ClientConnectionChangedIntent.class, ccci -> handleClientStatusChanged(ccci.getStatus()));
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
			sentPackets.notifyAll();
		}
	}
	
	private void handleClientPacket(Packet p) {
		if (p instanceof Acknowledge)
			onAcknowledge(((Acknowledge) p).getSequence());
		else if (p instanceof OutOfOrder)
			onOutOfOrder(((OutOfOrder) p).getSequence());
	}
	
	private void handleClientStatusChanged(ClientConnectionStatus status) {
		if (status == ClientConnectionStatus.DISCONNECTED) {
			restart();
		}
	}
	
	private void onOutOfOrder(short sequence) {
		Log.w("OOO %d", sequence);
		synchronized (sentPackets) {
			congAvoidance.onOutOfOrder();
			updateRtt();
		}
	}
	
	private void onAcknowledge(short sequence) {
		int seqInt = sequence & 0xFFFF;
		SequencedOutbound out;
		synchronized (sentPackets) {
			while (!sentPackets.isEmpty()) {
				out = sentPackets.get(0);
				if (out.getSequenceInt() <= seqInt || (seqInt < 100 && out.getSequenceInt() > Short.MAX_VALUE-100)) {
					sentPackets.remove(0);
				} else {
					break;
				}
			}
			updateRtt();
			congAvoidance.onAcknowledgement();
		}
	}
	
	private void resendRunnable() {
		while (resender.isRunning()) {
			synchronized (sentPackets) {
				congAvoidance.markBeginningOfWindow();
				if (congAvoidance.getWindow() != 50 && congAvoidance.getAverageRTT() != Double.MAX_VALUE) {
					Log.d("Congestion Window: %d  Average RTT: %.3fms", congAvoidance.getWindow(), congAvoidance.getAverageRTT()/1E6);
				}
				boolean lossEvent = !sentPackets.isEmpty() && (congAvoidance.isTimedOut() || congAvoidance.isTripleACK());
				if (lossEvent) {
					Log.w("Resender: Loss Event!");
				}
				sendAllInWindow();
				if (sentPackets.size() < 100) {
					casualWindowIteration(lossEvent);
				} else {
					intenseWindowIteration(lossEvent);
				}
				congAvoidance.markEndOfWindow();
				if (waitForPacket())
					continue;
			}
			waitForTimeoutOrAck();
		}
	}
	
	private void updateRtt() {
		long lastRtt = getTimeSinceSent();
		clearTimeSinceSent();
		if (lastRtt > 0)
			congAvoidance.updateRtt(lastRtt);
	}
	
	private void waitForTimeoutOrAck() {
		double avg = congAvoidance.getAverageRTT();
		if (avg == Double.MAX_VALUE)
			Delay.sleepMicro(5);
		else
			Delay.sleepNano((long) Math.min(1E9, Math.max(5E6, avg)));
	}
	
	private boolean waitForPacket() {
		if (!sentPackets.isEmpty())
			return false;
		try {
			while (sentPackets.isEmpty()) {
				sentPackets.wait();
			}
		} catch (InterruptedException e) {
		}
		clearTimeSinceSent();
		return true;
	}
	
	/**
	 * This congestion avoidance algorithm focuses more on slow window
	 * increases/decreases.  This is ideal for casual gameplay where there
	 * aren't many packets being sent at once.
	 */
	private void casualWindowIteration(boolean lossEvent) {
		if (lossEvent) {
			congAvoidance.setWindow(Math.max(10, congAvoidance.getWindow() - 5));
		} else {
			congAvoidance.setWindow(Math.min(50, congAvoidance.getWindow() + 5));
		}
	}
	
	/**
	 * This congestion avoidance algorithm focuses on fast window
	 * increases/decreases.  This is ideal for zone-in where as many packets as
	 * possible need to be sent as fast as possible.
	 */
	private void intenseWindowIteration(boolean lossEvent) {
		int window = congAvoidance.getWindow();
		if (window > sentPackets.size())
			return;
		if (lossEvent) {
			congAvoidance.setWindow(Math.max(50, window / 4));
		} else {
			congAvoidance.setWindow((int) (window * 1.5));
		}
	}
	
	private void sendAllInWindow() {
		int max = congAvoidance.getWindow();
		for (SequencedOutbound out : sentPackets) {
			if (--max < 0)
				break;
			sender.sendRaw(out.getData());
			congAvoidance.onSentPacket();
		}
		updateTimeSinceSent();
	}
	
	private void updateTimeSinceSent() {
		lastSent.compareAndSet(0, System.nanoTime());
	}
	
	private long getTimeSinceSent() {
		long sent = lastSent.get();
		if (sent == 0)
			return -1;
		return System.nanoTime() - sent;
	}
	
	private void clearTimeSinceSent() {
		lastSent.set(0);
	}
	
	private static class CongestionAvoidance {
		
		// Higher-level properties
		private double averageRtt;
		private int congestionWindow;
		
		// Lower-level properties
		private int outOfOrders;
		private int acknowledgements;
		private int sentPackets;
		private int missedWindows;
		
		public CongestionAvoidance() {
			this.averageRtt = Double.MAX_VALUE;
			reset();
		}
		
		public void reset() {
			this.outOfOrders = 0;
			this.acknowledgements = 0;
			this.sentPackets = 0;
			this.missedWindows = 0;
			this.congestionWindow = 1;
		}
		
		public void onOutOfOrder() {
			this.outOfOrders++;
		}
		
		public void updateRtt(long lastRtt) {
			if (this.averageRtt == Double.MAX_VALUE)
				this.averageRtt = lastRtt;
			else
				this.averageRtt = averageRtt * 0.875 + lastRtt * 0.125;
		}
		
		public void onAcknowledgement() {
			this.outOfOrders = 0;
			this.acknowledgements++;
		}
		
		public void onSentPacket() {
			this.sentPackets++;
		}
		
		public void markBeginningOfWindow() {
			if (acknowledgements == 0 && sentPackets > 0)
				missedWindows++;
			else
				missedWindows = 0;
			this.sentPackets = 0;
		}
		
		public void markEndOfWindow() {
			this.acknowledgements = 0;
		}
		
		public void setWindow(int congestionWindow) {
			this.congestionWindow = congestionWindow;
		}
		
		public double getAverageRTT() {
			return averageRtt;
		}
		
		public int getWindow() {
			return congestionWindow;
		}
		
		public boolean isTripleACK() {
			return outOfOrders >= 3;
		}
		
		public boolean isTimedOut() {
			return missedWindows >= 2 && averageRtt != Double.MAX_VALUE;
		}
		
	}
	
	private static class SequencedOutbound implements SequencedPacket {
		
		private int sequence;
		private byte [] data;
		
		public SequencedOutbound(short sequence, byte [] data) {
			this.sequence = sequence & 0xFFFF;
			this.data = data;
		}
		
		public short getSequence() { return (short) sequence; }
		public int getSequenceInt() { return sequence; }
		public byte [] getData() { return data; }
		
		public int compareTo(SequencedPacket p) {
			if (getSequence() < p.getSequence())
				return -1;
			if (getSequence() == p.getSequence())
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
	
}
