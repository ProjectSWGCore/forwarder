package com.projectswg.networking.client.sender;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

import com.projectswg.concurrency.PswgBasicThread;
import com.projectswg.concurrency.SmartLock;
import com.projectswg.concurrency.SynchronizedQueue;
import com.projectswg.control.Assert;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.client.ClientData;
import com.projectswg.networking.client.ClientPacketSender;
import com.projectswg.networking.soe.DataChannel;
import com.projectswg.networking.soe.Fragmented;
import com.projectswg.utilities.ThreadUtilities;

/**
 * This class is in charge of packaging up packets, and sending them on the
 * network for the first time
 */
public class PacketPackager {
	
	private final Queue<byte []> inboundQueue;
	private final SmartLock inboundQueueLock;
	private final PswgBasicThread packagerThread;
	private final Packager packager;
	
	public PacketPackager(ClientData data, NetInterceptor interceptor, ClientPacketSender sender) {
		this.inboundQueue = new SynchronizedQueue<>(new ArrayDeque<>(128));
		this.inboundQueueLock = new SmartLock();
		this.packagerThread = new PswgBasicThread("packet-packager", () -> packagerRunnable());
		this.packager = new Packager(interceptor, data, sender);
	}
	
	public void start() {
		Assert.test(inboundQueue.isEmpty(), "Inbound queue must be empty when starting!");
		packagerThread.start();
	}
	
	public void stop() {
		packagerThread.stop();
		packagerThread.awaitTermination(1000);
		inboundQueue.clear();
	}
	
	public void restart() {
		inboundQueue.clear();
	}
	
	public void addToPackage(byte [] data) {
		Assert.test(data.length > 0, "Array length must be greater than 0!");
		inboundQueue.add(data);
		signalInboundCondition();
	}
	
	private void packagerRunnable() {
		while (packagerThread.isRunning()) {
			if (packager.handle(inboundQueue) > 0) {
				ThreadUtilities.sleep(25);
			} else {
				awaitInboundCondition();
			}
		}
	}
	
	private void signalInboundCondition() {
		inboundQueueLock.signal();
	}
	
	private void awaitInboundCondition() {
		try {
			while (inboundQueue.isEmpty()) {
				inboundQueueLock.await();
			}
		} catch (InterruptedException e) {
			
		}
	}
	
	private static class Packager {
		
		private final NetInterceptor interceptor;
		private final ClientData clientData;
		private final ClientPacketSender packetSender;
		private final DataChannel channel;
		private int size;
		
		public Packager(NetInterceptor interceptor, ClientData clientData, ClientPacketSender packetSender) {
			this.interceptor = interceptor;
			this.clientData = clientData;
			this.packetSender = packetSender;
			this.channel = new DataChannel();
			reset();
		}
		
		/**
		 * Packages all packets in the queue appropriately
		 * @param queue the queue of raw packets
		 */
		public synchronized int handle(Queue<byte []> queue) {
			byte [] packet;
			int packetSize;
			int packets = 0;
			Assert.test(size == 8, "Internal Packager size must equal 8 at start of loop!");
			Assert.test(channel.getPacketCount() == 0, "Internal Packager DataChannel must be empty at start of loop!");
			while (!queue.isEmpty()) {
				packet = interceptor.interceptServer(queue.poll());
				packetSize = getPacketLength(packet);
				if (size + packetSize >= 496) {
					handleDataChannelOverflow(packet, packetSize);
				} else {
					addToChannel(packet, packetSize);
				}
				packets++;
			}
			sendDataChannel();
			return packets;
		}
		
		private void handleDataChannelOverflow(byte [] packet, int packetSize) {
			sendDataChannel();
			if (packetSize >= 496) {
				sendFragmented(packet);
				return;
			}
			addToChannel(packet, packetSize);
		}
		
		private void addToChannel(byte [] packet, int packetSize) {
			channel.addPacket(packet);
			size += packetSize;
		}
		
		private void sendDataChannel() {
			if (channel.getPacketCount() == 0)
				return;
			channel.setSequence(clientData.getAndIncrementTxSequence());
			packetSender.sendRaw(channel);
			reset();
		}
		
		private void sendFragmented(byte [] packet) {
			Fragmented [] frags = Fragmented.encode(ByteBuffer.wrap(packet), clientData.getTxSequence());
			clientData.setTxSequence((short) (clientData.getTxSequence() + frags.length));
			packetSender.sendRaw(frags);
		}
		
		private int getPacketLength(byte [] data) {
			int len = data.length;
			if (len >= 255)
				return len + 3;
			return len + 1;
		}
		
		private void reset() {
			channel.clearPackets();
			size = 8;
		}
		
	}
	
}
