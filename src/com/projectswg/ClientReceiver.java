package com.projectswg;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.Packet;
import com.projectswg.networking.UDPServer.UDPPacket;
import com.projectswg.networking.encryption.Encryption;
import com.projectswg.networking.soe.Acknowledge;
import com.projectswg.networking.soe.ClientNetworkStatusUpdate;
import com.projectswg.networking.soe.DataChannelA;
import com.projectswg.networking.soe.Disconnect;
import com.projectswg.networking.soe.Fragmented;
import com.projectswg.networking.soe.KeepAlive;
import com.projectswg.networking.soe.MultiPacket;
import com.projectswg.networking.soe.OutOfOrder;
import com.projectswg.networking.soe.ServerNetworkStatusUpdate;
import com.projectswg.networking.soe.SessionRequest;
import com.projectswg.networking.soe.SessionResponse;
import com.projectswg.networking.swg.ServerId;
import com.projectswg.networking.swg.ServerString;
import com.projectswg.utilities.ByteUtilities;

public class ClientReceiver {
	
	private static final int MAX_PACKET_SIZE = 496;
	
	private final NetInterceptor interceptor;
	private final AtomicLong lastPacket;
	private final List<Fragmented> fragmentedBuffer;
	private ExecutorService executor;
	private ClientSender sender;
	private ClientReceiverCallback callback;
	private ConnectionState state;
	private short rxSequence;
	private int port;
	private boolean zone;
	
	public ClientReceiver(NetInterceptor interceptor) {
		this.interceptor = interceptor;
		this.lastPacket = new AtomicLong(0);
		this.fragmentedBuffer = new ArrayList<>();
		setConnectionState(ConnectionState.DISCONNECTED);
		sender = null;
		callback = null;
		rxSequence = -1;
		port = 0;
		zone = false;
	}
	
	public void start() {
		executor = Executors.newSingleThreadExecutor();
	}
	
	public void stop() {
		executor.shutdownNow();
	}
	
	public void setClientSender(ClientSender sender) {
		this.sender = sender;
	}
	
	public void setReceiverCallback(ClientReceiverCallback callback) {
		this.callback = callback;
	}
	
	public void reset() {
		rxSequence = -1;
	}
	
	public double getTimeSinceLastPacket() {
		return (System.nanoTime() - lastPacket.get()) / 1E6;
	}
	
	public void onPacket(boolean zone, UDPPacket packet) {
		if (packet.getData().length < 2)
			return;
		if (zone && packet.getData().length == 4) { // Ping
			sender.sendRaw(packet.getPort(), packet.getData());
			lastPacket.set(System.nanoTime());
			if (callback != null)
				callback.onUdpRecv(zone, packet.getData());
			return;
		}
		ByteBuffer data = ByteBuffer.wrap(packet.getData()).order(ByteOrder.BIG_ENDIAN);
		short type = data.getShort(0);
		if (type == 1) {
			this.zone = zone;
			this.port = packet.getPort();
			sender.setZone(zone);
			sender.setPort(packet.getPort());
			lastPacket.set(System.nanoTime());
			process(data);
			if (callback != null)
				callback.onUdpRecv(zone, data.array());
		} else {
			if (packet.getPort() != port || port == 0)
				return;
			lastPacket.set(System.nanoTime());
			executor.execute(() -> {
				ByteBuffer decoded = ByteBuffer.wrap(Encryption.decode(data.array(), 0)).order(ByteOrder.BIG_ENDIAN);
				process(decoded);
				if (callback != null)
					callback.onUdpRecv(zone, decoded.array());
			});
		}
	}
	
	private void setConnectionState(ConnectionState state) {
		if (this.state != state && callback != null)
			callback.onConnectionChanged(state);
		this.state = state;
	}
	
	private void process(ByteBuffer data) {
		if (data.array().length < 2)
			return;
		if (data.get(0) != 0) {
			onSWGPacket(data.array());
			return;
		}
		switch (data.getShort(0)) {
			case 1: // Session Request
				onSessionRequest(new SessionRequest(data));
				break;
			case 3:
				onMultiPacket(new MultiPacket(data));
				break;
			case 5:
				onDisconnect(new Disconnect(data));
				break;
			case 6:
				onKeepAlive(new KeepAlive(data));
				break;
			case 7:
				onClientNetwork(new ClientNetworkStatusUpdate(data));
				break;
			case 9:
				onDataChannel(new DataChannelA(data));
				break;
			case 0x0D:
				onFragmented(new Fragmented(data));
				break;
			case 17:
				onOutOfOrder(new OutOfOrder(data));
				break;
			case 21:
				onAcknowledge(new Acknowledge(data));
				break;
			default:
				System.out.println("onPacket(addr=localhost:"+port+", type="+data.getShort(0)+", "+ByteUtilities.getHexString(data.array())+")");
				break;
		}
	}
	
	private void onSessionRequest(SessionRequest request) {
		if (callback != null && !zone)
			callback.onDisconnected();
		if (zone) {
			System.out.println("Zone Session Request");
			setConnectionState(ConnectionState.ZONE_CONNECTED);
		} else {
			System.out.println("Login Session Request");
			setConnectionState(ConnectionState.LOGIN_CONNECTED);
		}
		sender.setConnectionId(request.getConnectionID());
		sender.send(new SessionResponse(request.getConnectionID(), 0, (byte) 2, (byte) 1, (byte) 4, MAX_PACKET_SIZE));
		sender.send(new ServerString("ProjectSWG:1"), new ServerId(1));
		if (callback != null && !zone)
			callback.onConnected();
	}
	
	private void onMultiPacket(MultiPacket packet) {
		for (byte [] p : packet.getPackets()) {
			process(ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN));
		}
	}
	
	private void onDisconnect(Disconnect disconnect) {
		if (callback != null)
			callback.onDisconnected();
		setConnectionState(ConnectionState.DISCONNECTED);
		zone = false;
	}
	
	private void onKeepAlive(KeepAlive alive) {
		sender.send(new KeepAlive());
	}
	
	private void onClientNetwork(ClientNetworkStatusUpdate update) {
		ServerNetworkStatusUpdate serverNet = new ServerNetworkStatusUpdate();
		serverNet.setClientTickCount((short) update.getTick());
		serverNet.setServerSyncStampLong(0);
		int rx = rxSequence == -1 ? 0 : rxSequence;
		serverNet.setClientPacketsSent(rx);
		serverNet.setClientPacketsRecv(sender.getSequence());
		serverNet.setServerPacketsSent(sender.getSequence());
		serverNet.setServerPacketsRecv(rx);
		sender.send(serverNet);
	}
	
	private void onDataChannel(DataChannelA dataChannel) {
		if (dataChannel.getSequence() != rxSequence+1) {
			if (dataChannel.getSequence() > rxSequence)
				sender.send(new OutOfOrder(dataChannel.getSequence()));
			System.err.println("Invalid Sequence! Expected: " + (rxSequence+1) + "  Actual: " + dataChannel.getSequence());
			return;
		}
		rxSequence = dataChannel.getSequence();
		sender.send(new Acknowledge(rxSequence));
		for (byte [] data : dataChannel.getPackets()) {
			onSWGPacket(data);
		}
	}
	
	private void onFragmented(Fragmented frag) {
		if (frag.getSequence() != rxSequence+1) {
			if (frag.getSequence() > rxSequence)
				sender.send(new OutOfOrder(frag.getSequence()));
			System.err.println("Invalid Sequence! Expected: " + (rxSequence+1) + "  Actual: " + frag.getSequence());
			return;
		}
		rxSequence = frag.getSequence();
		sender.send(new Acknowledge(rxSequence));
		fragmentedBuffer.add(frag);
		frag = fragmentedBuffer.get(0);
		ByteBuffer data = frag.getPacketData();
		data.position(4);
		int size = Packet.getNetInt(data);
		int index = data.remaining();
		for (int i = 1; i < fragmentedBuffer.size() && index < size; i++)
			index += fragmentedBuffer.get(i).getPacketData().limit()-4;
		if (index == size)
			processFragmentedReady(size);
	}
	
	private void processFragmentedReady(int size) {
		ByteBuffer combined = ByteBuffer.allocate(size);
		while (combined.hasRemaining()) {
			ByteBuffer packet = fragmentedBuffer.get(0).getPacketData();
			packet.position(combined.position() == 0 ? 8 : 4);
			combined.put(packet);
			fragmentedBuffer.remove(0);
		}
		combined.flip();
		process(combined);
	}
	
	private void onOutOfOrder(OutOfOrder ooo) {
		sender.onOutOfOrder(ooo.getSequence());
	}
	
	private void onAcknowledge(Acknowledge ack) {
		sender.onAcknowledge(ack.getSequence());
	}
	
	private void onSWGPacket(byte [] data) {
		data = interceptor.interceptClient(data);
		if (callback != null)
			callback.onPacket(data);
	}
	
	public interface ClientReceiverCallback {
		void onConnected();
		void onDisconnected();
		void onConnectionChanged(ConnectionState state);
		void onPacket(byte [] data);
		void onUdpRecv(boolean zone, byte [] data);
	}
	
	public enum ConnectionState {
		DISCONNECTED,
		LOGIN_CONNECTED,
		ZONE_CONNECTED
	}
	
}
