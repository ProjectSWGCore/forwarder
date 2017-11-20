package com.projectswg.networking.client.sender;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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
import com.projectswg.resources.ClientConnectionStatus;

/**
 * This class is in charge of resending any unacknowledged packets
 */
public class ClientPacketResender {
	
	private final ResenderWrapper resender;
	private final PswgBasicThread executor;
	private final AtomicBoolean running;
	
	public ClientPacketResender(ClientPacketSender sender) {
		this.resender = new ResenderWrapper(sender);
		this.executor = new PswgBasicThread("packet-resender", this::resender);
		this.running = new AtomicBoolean(false);
	}
	
	public void start(IntentManager intentManager) {
		intentManager.registerForIntent(ClientSonyPacketIntent.class, cspi -> handleClientPacket(cspi.getPacket()));
		intentManager.registerForIntent(ClientConnectionChangedIntent.class, ccci -> handleClientStatusChanged(ccci.getStatus()));
		
		running.set(true);
		executor.start();
	}
	
	public void stop() {
		running.set(false);
		executor.stop(true);
		executor.awaitTermination(500);
		resender.reset();
	}
	
	public void add(SequencedOutbound out) {
		resender.add(out);
	}
	
	private void resender() {
		while (running.get()) {
			resender.resend();
		}
	}
	
	private void handleClientPacket(Packet p) {
		if (p instanceof Acknowledge)
			resender.onAcknowledge(((Acknowledge) p).getSequence());
		else if (p instanceof OutOfOrder)
			resender.onOutOfOrder(((OutOfOrder) p).getSequence());
	}
	
	private void handleClientStatusChanged(ClientConnectionStatus status) {
		resender.reset();
	}
	
	private static class ResenderWrapper {
		
		private final List<SequencedOutbound> sentPackets;
		private final CongestionAvoidance congAvoidance;
		private final Lock sentPacketsLock;
		private final Condition sentPacketsCondition;
		private final Resender resender;
		
		public ResenderWrapper(ClientPacketSender sender) {
			this.sentPackets = new LinkedList<>();
			this.congAvoidance = new CongestionAvoidance();
			this.sentPacketsLock = new ReentrantLock(false);
			this.sentPacketsCondition = sentPacketsLock.newCondition();
			this.resender = new Resender(congAvoidance, sender);
		}
		
		public void add(SequencedOutbound seq) {
			sentPacketsLock.lock();
			try {
				sentPackets.add(seq);
				sentPacketsCondition.signal();
			} finally {
				sentPacketsLock.unlock();
			}
		}
		
		public void reset() {
			sentPacketsLock.lock();
			try {
				sentPackets.clear();
			} finally {
				sentPacketsLock.unlock();
			}
		}
		
		public void onOutOfOrder(short sequence) {
			Log.w("OOO %d", sequence);
			sentPacketsLock.lock();
			try {
				congAvoidance.onOutOfOrder();
			} finally {
				sentPacketsLock.unlock();
			}
		}
		
		public void onAcknowledge(short sequence) {
			sentPacketsLock.lock();
			try {
				int seqInt = sequence & 0xFFFF;
				SequencedOutbound out;
				while (!sentPackets.isEmpty()) {
					out = sentPackets.get(0);
					if (out.getSequenceInt() <= seqInt || (seqInt < 100 && out.getSequenceInt() > Short.MAX_VALUE-100)) {
						sentPackets.remove(0);
					} else {
						break;
					}
				}
				congAvoidance.onAcknowledgement();
			} finally {
				sentPacketsLock.unlock();
			}
		}
		
		public void resend() {
			sentPacketsLock.lock();
			try {
				waitForPacket();
				resender.handle(sentPackets);
			} finally {
				sentPacketsLock.unlock();
			}
			waitForTimeoutOrAck();
		}
		
		private void waitForPacket() {
			if (!sentPackets.isEmpty())
				return;
			try {
				while (sentPackets.isEmpty()) {
					sentPacketsCondition.await();
				}
			} catch (InterruptedException e) {
				
			}
			congAvoidance.clearTimeSinceSent();
		}
		
		private void waitForTimeoutOrAck() {
			double avg = congAvoidance.getAverageRTT();
			if (avg == Double.MAX_VALUE)
				Delay.sleepMicro(5);
			else
				Delay.sleepNano((long) Math.min(1E9, Math.max(5E6, avg)));
		}
		
	}
	
	private static class Resender {
		
		private final CongestionAvoidance congAvoidance;
		private final ClientPacketSender sender;
		
		public Resender(CongestionAvoidance congAvoidance, ClientPacketSender sender) {
			this.congAvoidance = congAvoidance;
			this.sender = sender;
		}
		
		public void handle(List<SequencedOutbound> sentPackets) {
			congAvoidance.markBeginningOfWindow();
			int sentPacketCount = sentPackets.size();
			boolean lossEvent = sentPacketCount > 0 && (congAvoidance.isTimedOut() || congAvoidance.isTripleACK());
			
			printStatus(lossEvent);
			sendAllInWindow(sentPackets);
			if (sentPacketCount < 100) {
				casualWindowIteration(lossEvent);
			} else {
				intenseWindowIteration(lossEvent, sentPacketCount);
			}
			congAvoidance.markEndOfWindow();
		}
		
		private void printStatus(boolean lossEvent) {
			if (congAvoidance.getWindow() != 50 && congAvoidance.getAverageRTT() != Double.MAX_VALUE)
				Log.d("Resender: Congestion Window: %d  Average RTT: %.3fms", congAvoidance.getWindow(), congAvoidance.getAverageRTT()/1E6);
			
			if (lossEvent)
				Log.w("Resender: Loss Event!");
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
		private void intenseWindowIteration(boolean lossEvent, int sentPackets) {
			int window = congAvoidance.getWindow();
			if (window > sentPackets)
				return;
			if (lossEvent) {
				congAvoidance.setWindow(Math.max(50, window / 4));
			} else {
				congAvoidance.setWindow((int) (window * 1.5));
			}
		}
		
		private void sendAllInWindow(List<SequencedOutbound> sentPackets) {
			int max = congAvoidance.getWindow();
			for (SequencedOutbound out : sentPackets) {
				if (--max < 0)
					break;
				sender.sendRaw(out.getData());
				congAvoidance.onSentPacket();
			}
			congAvoidance.updateTimeSinceSent();
		}
		
	}
	
	private static class CongestionAvoidance {
		
		// Higher-level properties
		private long lastSent;
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
			this.lastSent = 0;
			this.congestionWindow = 1;
			
			this.outOfOrders = 0;
			this.acknowledgements = 0;
			this.sentPackets = 0;
			this.missedWindows = 0;
		}
		
		public void onOutOfOrder() {
			updateRtt();
			this.outOfOrders++;
		}
		
		public void onAcknowledgement() {
			updateRtt();
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
		
		public void updateTimeSinceSent() {
			if (lastSent == 0)
				lastSent = System.nanoTime();
		}
		
		private void updateRtt() {
			long lastRtt = lastSent;
			lastSent = 0;
			if (lastRtt <= 0)
				return;
			lastRtt = System.nanoTime() - lastRtt;
			
			if (this.averageRtt == Double.MAX_VALUE)
				this.averageRtt = lastRtt;
			else
				this.averageRtt = averageRtt * 0.875 + lastRtt * 0.125;
		}
		
		public void clearTimeSinceSent() {
			lastSent = 0;
		}
		
	}	
}
