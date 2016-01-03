package com.projectswg;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.UDPServer.UDPPacket;
import com.projectswg.networking.encryption.Encryption;
import com.projectswg.networking.soe.Acknowledge;
import com.projectswg.networking.soe.ClientNetworkStatusUpdate;
import com.projectswg.networking.soe.DataChannelA;
import com.projectswg.networking.soe.Disconnect;
import com.projectswg.networking.soe.KeepAlive;
import com.projectswg.networking.soe.MultiPacket;
import com.projectswg.networking.soe.OutOfOrder;
import com.projectswg.networking.soe.ServerNetworkStatusUpdate;
import com.projectswg.networking.soe.SessionRequest;
import com.projectswg.networking.soe.SessionResponse;
import com.projectswg.networking.swg.ServerId;
import com.projectswg.networking.swg.ServerString;

public class ClientReceiver {
	
	private static final int MAX_PACKET_SIZE = 496;
	
	private final NetInterceptor interceptor;
	private ExecutorService executor;
	private ClientSender sender;
	private ClientReceiverCallback callback;
	private ConnectionState state;
	private short rxSequence;
	private int port;
	private boolean zone;
	
	public ClientReceiver(NetInterceptor interceptor) {
		this.interceptor = interceptor;
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
	
	public void onPacket(boolean zone, UDPPacket packet) {
		if (packet.getData().length < 2)
			return;
		if (zone && packet.getData().length == 4) { // Ping
			sender.sendRaw(packet.getPort(), packet.getAddress(), packet.getData());
			return;
		}
		ByteBuffer data = ByteBuffer.wrap(packet.getData()).order(ByteOrder.BIG_ENDIAN);
		short type = data.getShort(0);
		if (type == 1) {
			this.zone = zone;
			this.port = packet.getPort();
			sender.setZone(zone);
			sender.setPort(packet.getPort());
			process(data);
		} else {
			if (packet.getPort() != port || port == 0)
				return;
			executor.submit(() -> {
				process(ByteBuffer.wrap(Encryption.decode(data.array(), 0)).order(ByteOrder.BIG_ENDIAN));
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
			case 17:
				onOutOfOrder(new OutOfOrder(data));
				break;
			case 21:
				onAcknowledge(new Acknowledge(data));
				break;
			default:
				System.out.println("onPacket(addr=localhost:"+port+", type="+data.getShort(0)+", "+Arrays.toString(data.array())+")");
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
		System.out.println("Disconnected");
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
			System.err.println("Invalid Sequence! Expected: " + (rxSequence+1) + "  Actual: " + dataChannel.getSequence());
			return;
		}
		rxSequence = dataChannel.getSequence();
		sender.send(new Acknowledge(rxSequence));
		for (byte [] data : dataChannel.getPackets()) {
			onSWGPacket(data);
		}
	}
	
	private void onOutOfOrder(OutOfOrder ooo) {
		System.out.println("OOO " + ooo.getSequence());
	}
	
	private void onAcknowledge(Acknowledge ack) {
		System.out.println("ACK " + ack.getSequence());
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
	}
	
	public enum ConnectionState {
		DISCONNECTED,
		LOGIN_CONNECTED,
		ZONE_CONNECTED
	}
	
}
