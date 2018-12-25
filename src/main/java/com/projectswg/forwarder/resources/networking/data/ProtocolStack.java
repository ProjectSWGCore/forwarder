package com.projectswg.forwarder.resources.networking.data;

import com.projectswg.forwarder.resources.networking.ClientServer;
import com.projectswg.forwarder.resources.networking.packets.Fragmented;
import com.projectswg.forwarder.resources.networking.packets.Packet;
import com.projectswg.forwarder.resources.networking.packets.SequencedPacket;
import me.joshlarson.jlcommon.log.Log;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;

public class ProtocolStack {
	
	private final FragmentedProcessor fragmentedProcessor;
	private final InetSocketAddress source;
	private final BiConsumer<InetSocketAddress, byte[]> sender;
	private final ClientServer server;
	private final BlockingQueue<byte []> outboundRaw;
	private final ConnectionStream<SequencedPacket> inbound;
	private final ConnectionStream<SequencedOutbound> outbound;
	private final Packager packager;
	
	private InetSocketAddress pingSource;
	private int connectionId;
	
	public ProtocolStack(InetSocketAddress source, ClientServer server, BiConsumer<InetSocketAddress, byte[]> sender) {
		this.fragmentedProcessor = new FragmentedProcessor();
		this.source = source;
		this.sender = sender;
		this.server = server;
		this.outboundRaw = new LinkedBlockingQueue<>();
		this.inbound = new ConnectionStream<>();
		this.outbound = new ConnectionStream<>();
		this.packager = new Packager(outboundRaw, outbound, this);
		
		this.connectionId = 0;
	}
	
	public void send(Packet packet) {
		Log.t("Sending %s", packet);
		send(packet.encode().array());
	}
	
	public void send(byte [] data) {
		sender.accept(source, data);
	}
	
	public void sendPing(byte [] data) {
		InetSocketAddress pingSource = this.pingSource;
		if (pingSource != null)
			sender.accept(pingSource, data);
	}
	
	public InetSocketAddress getSource() {
		return source;
	}
	
	public ClientServer getServer() {
		return server;
	}
	
	public int getConnectionId() {
		return connectionId;
	}
	
	public short getRxSequence() {
		return inbound.getSequence();
	}
	
	public short getTxSequence() {
		return outbound.getSequence();
	}
	
	public void setPingSource(InetSocketAddress source) {
		this.pingSource = source;
	}
	
	public void setConnectionId(int connectionId) {
		this.connectionId = connectionId;
	}
	
	public SequencedStatus addIncoming(@NotNull SequencedPacket packet) {
		return inbound.addUnordered(packet);
	}
	
	public SequencedPacket getNextIncoming() {
		return inbound.poll();
	}
	
	public byte [] addFragmented(Fragmented frag) {
		return fragmentedProcessor.addFragmented(frag);
	}
	
	public void addOutbound(@NotNull byte [] data) {
		try {
			outboundRaw.put(data);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}
	
	public short getFirstUnacknowledgedOutbound() {
		SequencedOutbound out = outbound.peek();
		if (out == null)
			return -1;
		return out.getSequence();
	}
	
	public void clearAcknowledgedOutbound(short sequence) {
		outbound.removeOrdered(sequence);
	}
	
	public synchronized void fillOutboundPackagedBuffer(int maxPackaged) {
		packager.handle(maxPackaged);
	}
	
	public synchronized int fillOutboundBuffer(SequencedOutbound [] buffer) {
		return outbound.fillBuffer(buffer);
	}
	
	@Override
	public String toString() {
		return String.format("ProtocolStack[server=%s, source=%s, connectionId=%d]", server, source, connectionId);
	}
	
	public static class ConnectionStream<T extends SequencedPacket> {
		
		private final PriorityQueue<T> sequenced;
		private final PriorityQueue<T> queued;
		
		private long sequence;
		
		public ConnectionStream() {
			this.sequenced = new PriorityQueue<>();
			this.queued = new PriorityQueue<>();
		}
		
		public short getSequence() {
			return (short) sequence;
		}
		
		public synchronized SequencedStatus addUnordered(@NotNull T packet) {
			if (SequencedPacket.compare(getSequence(), packet.getSequence()) > 0) {
				T peek = peek();
				return peek != null && peek.getSequence() == getSequence() ? SequencedStatus.READY : SequencedStatus.STALE;
			}
			
			if (packet.getSequence() == getSequence()) {
				sequenced.add(packet);
				sequence++;
				
				// Add queued OOO packets
				T queue;
				while ((queue = queued.peek()) != null && queue.getSequence() == getSequence()) {
					sequenced.add(queued.poll());
					sequence++;
				}
				
				return SequencedStatus.READY;
			} else {
				queued.add(packet);
				
				return SequencedStatus.OUT_OF_ORDER;
			}
		}
		
		public synchronized void addOrdered(@NotNull T packet) {
			packet.setSequence(getSequence());
			addUnordered(packet);
		}
		
		public synchronized void removeOrdered(short sequence) {
			T packet;
			List<Short> sequencesRemoved = new ArrayList<>();
			while ((packet = sequenced.peek()) != null && SequencedPacket.compare(sequence, packet.getSequence()) >= 0) {
				T removed = sequenced.poll();
				assert packet == removed;
				sequencesRemoved.add(packet.getSequence());
			}
			Log.t("Removed acknowledged: %s", sequencesRemoved);
		}
		
		public synchronized T peek() {
			return sequenced.peek();
		}
		
		public synchronized T poll() {
			return sequenced.poll();
		}
		
		public synchronized int fillBuffer(T [] buffer) {
			int n = 0;
			for (T packet : sequenced) {
				if (n >= buffer.length)
					break;
				buffer[n++] = packet;
			}
			return n;
		}
		
		public int size() {
			return sequenced.size();
		}
		
	}
	
	public enum SequencedStatus {
		READY,
		OUT_OF_ORDER,
		STALE
	}
	
}
