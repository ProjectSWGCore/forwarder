package com.projectswg.forwarder.resources.networking.data;

import com.projectswg.forwarder.resources.networking.ClientServer;
import com.projectswg.forwarder.resources.networking.packets.Fragmented;
import com.projectswg.forwarder.resources.networking.packets.Packet;
import com.projectswg.forwarder.resources.networking.packets.SequencedPacket;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.function.BiConsumer;

public class ProtocolStack {
	
	private final PriorityQueue<SequencedPacket> sequenced;
	private final FragmentedProcessor fragmentedProcessor;
	private final InetSocketAddress source;
	private final BiConsumer<InetSocketAddress, byte[]> sender;
	private final ClientServer server;
	private final Queue<byte []> outboundRaw;
	private final Queue<SequencedOutbound> outboundPackaged;
	private final Packager packager;
	private final Object txMutex;
	
	private InetSocketAddress pingSource;
	private int connectionId;
	private short rxSequence;
	private short txSequence;
	private boolean txOverflow;
	
	public ProtocolStack(InetSocketAddress source, ClientServer server, BiConsumer<InetSocketAddress, byte[]> sender) {
		this.sequenced = new PriorityQueue<>();
		this.fragmentedProcessor = new FragmentedProcessor();
		this.source = source;
		this.sender = sender;
		this.server = server;
		this.outboundRaw = new LinkedList<>();
		this.outboundPackaged = new LinkedList<>();
		this.packager = new Packager(outboundRaw, outboundPackaged, this);
		this.txMutex = new Object();
		
		this.connectionId = 0;
		this.rxSequence = 0;
		this.txSequence = 0;
		this.txOverflow = false;
	}
	
	public void send(Packet packet) {
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
		return rxSequence;
	}
	
	public short getTxSequence() {
		return txSequence;
	}
	
	public void setPingSource(InetSocketAddress source) {
		this.pingSource = source;
	}
	
	public void setConnectionId(int connectionId) {
		this.connectionId = connectionId;
	}
	
	public short getAndIncrementTxSequence() {
		return getAndIncrementTxSequence(1);
	}
	
	public short getAndIncrementTxSequence(int amount) {
		synchronized (txMutex) {
			short prev = this.txSequence;
			short next = prev;
			next += amount;
			if (prev > next)
				txOverflow = true;
			this.txSequence = next;
			return prev;
		}
	}
	
	public boolean addIncoming(@NotNull SequencedPacket packet) {
		synchronized (sequenced) {
			if (packet.getSequence() < rxSequence)
				return true;
			// If it already exists in here, don't add it again
			for (SequencedPacket seq : sequenced) {
				if (seq.getSequence() == packet.getSequence())
					return true;
			}
			sequenced.add(packet);
			packet = sequenced.peek();
			assert packet != null : "the world is on fire";
			return packet.getSequence() == rxSequence;
		}
	}
	
	public SequencedPacket getNextIncoming() {
		synchronized (sequenced) {
			SequencedPacket peek = sequenced.peek();
			if (peek == null || peek.getSequence() != rxSequence)
				return null;
			rxSequence++;
			return sequenced.poll();
		}
	}
	
	public byte [] addFragmented(Fragmented frag) {
		return fragmentedProcessor.addFragmented(frag);
	}
	
	public void addOutbound(@NotNull byte [] data) {
		outboundRaw.offer(data);
	}
	
	public short getFirstUnacknowledgedOutbound() {
		SequencedOutbound out = outboundPackaged.peek();
		if (out == null)
			return -1;
		return out.getSequence();
	}
	
	public void clearAcknowledgedOutbound(short sequence) {
		synchronized (txMutex) {
			if (txOverflow && sequence <= txSequence) {
				outboundPackaged.removeIf(out -> out.getSequence() > txSequence);
				txOverflow = false;
			}
			SequencedOutbound out = outboundPackaged.peek();
			while (out != null && out.getSequence() <= sequence) {
				outboundPackaged.poll();
				out = outboundPackaged.peek();
			}
		}
	}
	
	public void fillOutboundPackagedBuffer(int maxPackaged) {
		packager.handle(maxPackaged);
	}
	
	public Collection<SequencedOutbound> getOutboundPackagedBuffer() {
		return Collections.unmodifiableCollection(outboundPackaged);
	}
	
}
