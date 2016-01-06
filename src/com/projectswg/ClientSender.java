package com.projectswg;

import java.net.BindException;
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
	
	private final NetInterceptor interceptor;
	private UDPServer loginServer;
	private UDPServer zoneServer;
	private Queue<SequencedOutbound> sentPackets;
	private Queue<byte []> inboundQueue;
	private ExecutorService executor;
	private ClientSenderCallback callback;
	private ClientReceiver receiver;
	private short txSequence;
	private int connectionId;
	private int port;
	private int loginPort;
	private boolean zone;
	
	public ClientSender(NetInterceptor interceptor, int loginPort) {
		this.interceptor = interceptor;
		this.loginPort = loginPort;
		sentPackets = new LinkedList<>();
		inboundQueue = new LinkedList<>();
		connectionId = -1;
		txSequence = 0;
		port = 0;
		zone = false;
	}
	
	public boolean start() {
		safeCloseServers();
		try {
			loginServer = new UDPServer(loginPort, 496);
			zoneServer = new UDPServer(0, 496);
			executor = Executors.newFixedThreadPool(2);
			executor.submit(() -> outboundRunnable());
			executor.submit(() -> inboundRunnable());
			return true;
		} catch (BindException e) {
			System.err.println("Failed to bind UDP servers! Login Port: " + loginPort);
		} catch (SocketException e) {
			e.printStackTrace();
		}
		safeCloseServers();
		executor = null;
		return false;
	}
	
	public void stop() {
		disconnect(DisconnectReason.APPLICATION);
		executor.shutdownNow();
		safeCloseServers();
	}
	
	public void setClientReceiver(ClientReceiver receiver) {
		this.receiver = receiver;
	}
	
	public void setLoginCallback(UDPCallback callback) {
		loginServer.setCallback(callback);
	}
	
	public void setZoneCallback(UDPCallback callback) {
		zoneServer.setCallback(callback);
	}
	
	public void setSenderCallback(ClientSenderCallback callback) {
		this.callback = callback;
	}
	
	public void setLoginPort(int loginPort) {
		this.loginPort = loginPort;
	}
	
	public int getLoginPort() {
		if (loginServer == null)
			return -1;
		return loginServer.getPort();
	}
	
	public int getZonePort() {
		if (zoneServer == null)
			return -1;
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
			send(new Disconnect(connectionId, reason));
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
		if (callback != null)
			callback.onUdpSent(zone, data);
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
		double time = 0;
		long lastBurst = 0;
		double timeSinceBurst = 0;
		while (true) {
			time = receiver.getTimeSinceLastPacket();
			timeSinceBurst = (System.nanoTime() - lastBurst) / 1E6;
			if (time <= timeSinceBurst || (time >= 100 && timeSinceBurst >= 300)) {
				synchronized (sentPackets) {
					int sent = 0;
					for (SequencedOutbound packet : sentPackets) {
						sendRaw(port, addr, packet.getData());
						Thread.yield();
						if (++sent >= 500)
							break;
					}
				}
				lastBurst = System.nanoTime();
			}
			try {
				Thread.sleep(25);
			} catch (InterruptedException e) {
				break;
			}
		}
	}
	
	private void inboundRunnable() {
		final int dataHeaderSize = 8;
		byte [] data;
		Queue<byte []> outbound = new ArrayBlockingQueue<>(8, true);
		int size = 0;
		boolean lastWasEmpty = false;
		while (true) {
			size = dataHeaderSize;
			synchronized (inboundQueue) {
				if (inboundQueue.isEmpty() && lastWasEmpty) {
					try { inboundQueue.wait(); } catch (InterruptedException e) { break; }
				}
				lastWasEmpty = inboundQueue.isEmpty();
				while (!inboundQueue.isEmpty()) {
					data = interceptor.interceptServer(inboundQueue.poll());
					if ((size + getPacketLength(data) >= 496 && !outbound.isEmpty()) || outbound.size() == 8) {
						createOutboundPacket(outbound, size);
						size = dataHeaderSize;
					}
					outbound.add(data);
					size += getPacketLength(data);
					if (size >= 496) {
						createOutboundPacket(outbound, size);
						size = dataHeaderSize;
					}
					Thread.yield();
				}
			}
			if (!outbound.isEmpty())
				createOutboundPacket(outbound, size);
			try {
				Thread.sleep(25);
			} catch (InterruptedException e) {
				break;
			}
		}
	}
	
	private int getPacketLength(byte [] data) {
		if (data.length >= 255)
			return data.length + 3;
		else
			return data.length + 1;
	}
	
	private void createOutboundPacket(Queue<byte []> outbound, int size) {
		if (size <= 496) { // Fits into single data packet
			DataChannelA channel = new DataChannelA();
			channel.setSequence(txSequence++);
			while (!outbound.isEmpty())
				channel.addPacket(outbound.poll());
			send(channel);
		} else { // Fragmented
			while (!outbound.isEmpty()) {
				Fragmented [] frags = Fragmented.encode(ByteBuffer.wrap(outbound.poll()), txSequence);
				txSequence += frags.length;
				for (Fragmented frag : frags) {
					send(frag);
				}
			}
		}
	}
	
	private void safeCloseServers() {
		if (loginServer != null) {
			loginServer.close();
			loginServer = null;
		}
		if (zoneServer != null) {
			zoneServer.close();
			zoneServer = null;
		}
	}
	
	public interface ClientSenderCallback {
		void onUdpSent(boolean zone, byte [] data);
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
