package com.projectswg.networking.client.sender;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.projectswg.common.concurrency.Delay;
import com.projectswg.common.concurrency.PswgBasicThread;
import com.projectswg.common.control.IntentManager;
import com.projectswg.common.debug.Assert;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.client.ClientData;
import com.projectswg.networking.client.ClientPacketSender;
import com.projectswg.networking.soe.DataChannel;
import com.projectswg.networking.soe.Fragmented;
import com.projectswg.resources.ClientConnectionStatus;

public class ClientPackager {
	
	private final NetInterceptor interceptor;
	private final ClientPacketResender packetResender;
	private final PackagingWrapper packager;
	
	public ClientPackager(NetInterceptor interceptor, ClientData clientData, ClientPacketSender sender) {
		this.interceptor = interceptor;
		this.packetResender = new ClientPacketResender(sender);
		this.packager = new PackagingWrapper(packetResender, clientData);
	}
	
	public void start(IntentManager intentManager) {
		packetResender.start(intentManager);
		packager.start();
		intentManager.registerForIntent(ClientConnectionChangedIntent.class, ccci -> handleClientStatusChanged(ccci.getStatus()));
	}
	
	public void stop() {
		packager.stop();
		packetResender.stop();
	}
	
	public void clear() {
		packager.reset();
	}
	
	public void addToPackage(byte [] data) {
		Assert.test(data.length > 0, "Array length must be greater than 0!");
		packager.add(interceptor.interceptServer(data));
	}
	
	private void handleClientStatusChanged(ClientConnectionStatus status) {
		clear();
	}
	
	private static class PackagingWrapper {
		
		private final AtomicBoolean running;
		private final PswgBasicThread packagerThread;
		private final ClientPacketResender packetResender;
		private final Packager packager;
		private final Queue<byte []> inboundQueue;
		private final Lock inboundQueueLock;
		private final Condition inboundQueueCondition;
		
		public PackagingWrapper(ClientPacketResender packetResender, ClientData clientData) {
			this.running = new AtomicBoolean(false);
			this.packagerThread = new PswgBasicThread("packet-packager", this::loop);
			this.packetResender = packetResender;
			this.packager = new Packager(clientData);
			this.inboundQueue = new ArrayDeque<>(128);
			this.inboundQueueLock = new ReentrantLock(false);
			this.inboundQueueCondition = inboundQueueLock.newCondition();
		}
		
		public void start() {
			running.set(true);
			packagerThread.start();
		}
		
		public void stop() {
			running.set(false);
			packagerThread.stop(true);
			packagerThread.awaitTermination(500);
		}
		
		public void reset() {
			inboundQueue.clear();
		}
		
		public void add(byte [] packet) {
			inboundQueueLock.lock();
			try {
				inboundQueue.add(packet);
				inboundQueueCondition.signal();
			} finally {
				inboundQueueLock.unlock();
			}
		}
		
		private void loop() {
			Queue<SequencedOutbound> outboundQueue = new ArrayDeque<>(64);
			while (running.get()) {
				if (!waitForInbound())
					break;
				processInbound(outboundQueue);
				Delay.sleepMicro(25); // Accumulates some packets
			}
		}
		
		/**
		 * Waits for the queue to be non-empty and acquires the lock
		 * @return TRUE if the lock is acquired and the queue is non-empty, FALSE otherwise
		 */
		private boolean waitForInbound() {
			inboundQueueLock.lock();
			while (inboundQueue.isEmpty()) {
				try {
					inboundQueueCondition.await();
				} catch (InterruptedException e) {
					running.set(false);
					return false;
				}
			}
			return true;
		}
		
		private void processInbound(Queue<SequencedOutbound> outboundQueue) {
			try {
				packager.handle(inboundQueue, outboundQueue);
			} finally {
				inboundQueueLock.unlock();
			}
			
			while (!outboundQueue.isEmpty()) {
				packetResender.add(outboundQueue.poll());
			}
		}
		
	}
	
	private static class Packager {
		
		private final AtomicInteger size;
		private final DataChannel channel;
		private final ClientData clientData;
		
		public Packager(ClientData clientData) {
			this.size = new AtomicInteger(8);
			this.channel = new DataChannel();
			this.clientData = clientData;
		}
		
		/**
		 * Processes the inbound queue, and then sends it to the outbound queue
		 * @param inboundQueue inbound queue from the server
		 * @param outboundQueue outbound queue to the client
		 */
		public void handle(Queue<byte []> inboundQueue, Queue<SequencedOutbound> outboundQueue) {
			byte [] packet;
			int packetSize;
			
			while (!inboundQueue.isEmpty()) {
				packet = inboundQueue.poll();
				packetSize = getPacketLength(packet);
				
				if (size.get() + packetSize >= 496) // overflowed previous packet
					sendDataChannel(outboundQueue);
				
				if (packetSize < 496) {
					addToDataChannel(packet, packetSize);
				} else {
					sendFragmented(outboundQueue, packet);
				}
			}
			sendDataChannel(outboundQueue);
		}
		
		private void addToDataChannel(byte [] packet, int packetSize) {
			channel.addPacket(packet);
			size.getAndAdd(packetSize);
		}
		
		private void sendDataChannel(Queue<SequencedOutbound> outboundQueue) {
			if (channel.getPacketCount() == 0)
				return;
			
			channel.setSequence(clientData.getAndIncrementTxSequence());
			outboundQueue.add(new SequencedOutbound(channel.getSequence(), channel.encode().array()));
			reset();
		}
		
		private void sendFragmented(Queue<SequencedOutbound> outboundQueue, byte [] packet) {
			Fragmented [] frags = Fragmented.encode(ByteBuffer.wrap(packet), clientData.getTxSequence());
			clientData.setTxSequence((short) (clientData.getTxSequence() + frags.length));
			for (Fragmented frag : frags) {
				outboundQueue.add(new SequencedOutbound(frag.getSequence(), frag.encode().array()));
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
	
}
