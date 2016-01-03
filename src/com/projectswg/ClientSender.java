package com.projectswg;

import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.Packet;
import com.projectswg.networking.SWGPacket;
import com.projectswg.networking.UDPServer;
import com.projectswg.networking.UDPServer.UDPCallback;
import com.projectswg.networking.encryption.Encryption;
import com.projectswg.networking.soe.DataChannelA;
import com.projectswg.networking.soe.Disconnect;
import com.projectswg.networking.soe.Fragmented;
import com.projectswg.networking.soe.SequencedPacket;
import com.projectswg.networking.soe.SessionResponse;
import com.projectswg.networking.soe.Disconnect.DisconnectReason;

public class ClientSender {
	
	private static final InetAddress ADDR = InetAddress.getLoopbackAddress();
	private static final int MAX_PACKET_SIZE = 496;
	
	private final NetInterceptor interceptor;
	private UDPServer loginServer;
	private UDPServer zoneServer;
	private Queue<SequencedOutbound> sentPackets;
	private Queue<byte []> inboundQueue;
	private ExecutorService executor;
	private short txSequence;
	private int connectionId;
	private int port;
	private int loginPort;
	private int zonePort;
	private boolean zone;
	
	public ClientSender(NetInterceptor interceptor, int loginPort, int zonePort) {
		this.interceptor = interceptor;
		this.loginPort = loginPort;
		this.zonePort = zonePort;
		sentPackets = new LinkedList<>();
		inboundQueue = new LinkedList<>();
		connectionId = -1;
		txSequence = 0;
		port = 0;
		zone = false;
	}
	
	public void start() {
		try {
			loginServer = new UDPServer(loginPort, 496);
			zoneServer = new UDPServer(zonePort, 496);
		} catch (SocketException e) {
			e.printStackTrace();
			loginServer = null;
			zoneServer = null;
		}
		executor = Executors.newFixedThreadPool(2);
		executor.submit(() -> outboundRunnable());
		executor.submit(() -> inboundRunnable());
	}
	
	public void stop() {
		disconnect(DisconnectReason.APPLICATION);
		executor.shutdownNow();
		loginServer.close();
		zoneServer.close();
	}
	
	public void setLoginCallback(UDPCallback callback) {
		loginServer.setCallback(callback);
	}
	
	public void setZoneCallback(UDPCallback callback) {
		zoneServer.setCallback(callback);
	}
	
	public int getZonePort() {
		return zoneServer.getPort();
	}
	
	public int getSequence() {
		return txSequence;
	}
	
	public void setZone(boolean zone) {
		this.zone = zone;
	}
	
	public void setPort(int port) {
		this.port = port;
	}
	
	public void setConnectionId(int connectionId) {
		this.connectionId = connectionId;
	}
	
	public void disconnect(DisconnectReason reason) {
		if (connectionId != -1)
			sendRaw(new Disconnect(connectionId, reason).encode().array());
		connectionId = -1;
	}
	
	public void reset() {
		synchronized (sentPackets) {
			sentPackets.clear();
		}
		txSequence = 0;
	}
	
	public void onAcknowledge(short sequence) {
		synchronized (sentPackets) {
			while (!sentPackets.isEmpty()) {
				if (sentPackets.peek().getSequence() <= sequence)
					sentPackets.poll();
				else
					break;
			}
		}
	}
	
	public void send(SWGPacket ... packets) {
		for (SWGPacket packet : packets) {
			send(packet.encode().array());
		}
	}
	
	public void send(byte [] data) {
		synchronized (inboundQueue) {
			inboundQueue.add(data);
			inboundQueue.notifyAll();
		}
	}
	
	public void sendRaw(byte [] data) {
		sendRaw(port, ADDR, data);
	}
	
	public void sendRaw(int port, InetAddress addr, byte [] data) {
		if (zone)
			zoneServer.send(port, addr, data);
		else
			loginServer.send(port, addr, data);
	}
	
	public void send(Packet packet) {
		byte [] data;
		if (packet instanceof SessionResponse)
			data = packet.encode().array();
		else
			data = Encryption.encode(packet.encode().array(), 0);
		if (packet instanceof SequencedPacket) {
			synchronized (sentPackets) {
				sentPackets.add(new SequencedOutbound(((SequencedPacket) packet).getSequence(), data));
				sentPackets.notifyAll();
			}
		} else {
			sendRaw(port, InetAddress.getLoopbackAddress(), data);
		}
	}
	
	private void outboundRunnable() {
		final InetAddress addr = InetAddress.getLoopbackAddress();
		while (true) {
			synchronized (sentPackets) {
				int sent = 0;
				for (SequencedOutbound packet : sentPackets) {
					sendRaw(port, addr, packet.getData());
					Thread.yield();
					if (++sent >= 500)
						break;
				}
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				break;
			}
		}
	}
	
	private void inboundRunnable() {
		byte [] data;
		Queue<byte []> outbound = new ArrayBlockingQueue<>(32, true);
		int size = 0;
		while (true) {
			size = 6;
			synchronized (inboundQueue) {
				while (!inboundQueue.isEmpty()) {
					data = interceptor.interceptServer(inboundQueue.poll());
					if ((size + data.length > MAX_PACKET_SIZE-7 && !outbound.isEmpty()) || outbound.size() == 32) {
						createOutboundPacket(outbound, size);
						size = 6;
					}
					outbound.add(data);
					size += data.length + 1 + (data.length >= 0xFF ? 2 : 0);
					if (size > MAX_PACKET_SIZE-4) {
						createOutboundPacket(outbound, size);
						size = 6;
					}
					Thread.yield();
				}
			}
			if (!outbound.isEmpty())
				createOutboundPacket(outbound, size);
			size = 0;
			try {
				Thread.sleep(25);
			} catch (InterruptedException e) {
				break;
			}
		}
	}
	
	private void createOutboundPacket(Queue<byte []> outbound, int size) {
		if (size <= MAX_PACKET_SIZE-4) { // Fits into single data packet
			DataChannelA channel = new DataChannelA();
			channel.setSequence(txSequence++);
			while (!outbound.isEmpty())
				channel.addPacket(outbound.poll());
			send(channel);
		} else { // Fragmented
			while (!outbound.isEmpty()) {
				Fragmented [] frags = Fragmented.encode(ByteBuffer.wrap(outbound.poll()), txSequence, MAX_PACKET_SIZE);
				txSequence += frags.length;
				for (Fragmented frag : frags) {
					send(frag);
				}
			}
		}
	}
	
	private static class SequencedOutbound implements SequencedPacket {
		
		private short sequence;
		private byte [] data;
		
		public SequencedOutbound(short sequence, byte [] data) {
			this.sequence = sequence;
			this.data = data;
		}
		
		public short getSequence() { return sequence; }
		public byte [] getData() { return data; }
		
		public int compareTo(SequencedPacket p) {
			if (sequence < p.getSequence())
				return -1;
			if (sequence == p.getSequence())
				return 0;
			return 1;
		}
	}
	
}
