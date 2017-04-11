package com.projectswg.networking.client.sender;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

import com.projectswg.common.concurrency.Delay;
import com.projectswg.common.concurrency.PswgBasicThread;
import com.projectswg.common.concurrency.SmartLock;
import com.projectswg.common.concurrency.SynchronizedQueue;
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
	private final ClientData clientData;
	private final ClientPacketResender packetResender;
	private final PswgBasicThread packagerThread;
	private final Queue<byte []> inboundQueue;
	private final SmartLock inboundQueueLock;
	private final DataChannel channel;
	private int size;
	
	public ClientPackager(NetInterceptor interceptor, ClientData clientData, ClientPacketSender sender) {
		this.interceptor = interceptor;
		this.clientData = clientData;
		this.inboundQueue = new SynchronizedQueue<>(new ArrayDeque<>(128));
		this.inboundQueueLock = new SmartLock();
		this.packagerThread = new PswgBasicThread("packet-packager", () -> packagerRunnable());
		this.packagerThread.setInterruptOnStop(true);
		this.packetResender = new ClientPacketResender(sender);
		this.channel = new DataChannel();
		reset();
	}
	
	public void start(IntentManager intentManager) {
		Assert.test(inboundQueue.isEmpty(), "Inbound queue must be empty when starting!");
		packagerThread.start();
		packetResender.start(intentManager);
		intentManager.registerForIntent(ClientConnectionChangedIntent.class, ccci -> handleClientStatusChanged(ccci.getStatus()));
	}
	
	public void stop() {
		packetResender.stop();
		packagerThread.stop();
		packagerThread.awaitTermination(1000);
		inboundQueue.clear();
	}
	
	public void clear() {
		inboundQueue.clear();
	}
	
	public void addToPackage(byte [] data) {
		Assert.test(data.length > 0, "Array length must be greater than 0!");
		inboundQueue.add(data);
		inboundQueueLock.signal();
	}
	
	private void handleClientStatusChanged(ClientConnectionStatus status) {
		if (status == ClientConnectionStatus.DISCONNECTED) {
			clear();
		}
	}
	
	private void packagerRunnable() {
		while (packagerThread.isRunning()) {
			if (handle(inboundQueue) > 0) {
				if (Delay.sleepMicro(25))
					break;
			} else {
				try {
					while (inboundQueue.isEmpty()) {
						inboundQueueLock.await();
					}
				} catch (InterruptedException e) {
				}
			}
		}
	}
	
	/**
	 * Packages all packets in the queue appropriately
	 * @param queue the queue of raw packets
	 */
	private synchronized int handle(Queue<byte []> queue) {
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
		packetResender.add(channel.getSequence(), channel.encode().array());
		reset();
	}
	
	private void sendFragmented(byte [] packet) {
		Fragmented [] frags = Fragmented.encode(ByteBuffer.wrap(packet), clientData.getTxSequence());
		clientData.setTxSequence((short) (clientData.getTxSequence() + frags.length));
		for (Fragmented frag : frags) {
			packetResender.add(frag.getSequence(), frag.encode().array());
		}
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
