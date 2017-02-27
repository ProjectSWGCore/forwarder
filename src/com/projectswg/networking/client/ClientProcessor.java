package com.projectswg.networking.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import network.packets.swg.login.ServerId;
import network.packets.swg.login.ServerString;

import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.Packet;
import com.projectswg.networking.client.ClientServerSocket.IncomingPacket;
import com.projectswg.networking.client.sender.PacketResender;
import com.projectswg.networking.soe.*;
import com.projectswg.resources.ClientConnectionStatus;
import com.projectswg.utilities.ByteUtilities;
import com.projectswg.utilities.Log;

public class ClientProcessor {
	
	private static final int MAX_PACKET_SIZE = 496;
	private static final int GALACTIC_BASE_TIME = 1323043200;
	
	private final NetInterceptor interceptor;
	private final ClientData data;
	private final List<Fragmented> fragmentedBuffer;
	private final ClientPacketSender packetSender;
	private final AtomicReference<ClientConnectionStatus> status;
	private final PacketResender resender;
	private final AtomicLong lastPacket;
	private ClientToServerPacketCallback packetCallback;
	private ClientStatusCallback statusCallback;
	
	public ClientProcessor(NetInterceptor interceptor, ClientData data, ClientPacketSender packetSender) {
		this.interceptor = interceptor;
		this.data = data;
		this.fragmentedBuffer = new ArrayList<>();
		this.packetSender = packetSender;
		this.resender = new PacketResender(packetSender);
		this.lastPacket = new AtomicLong(0);
		this.statusCallback = null;
		this.packetCallback = null;
		this.status = new AtomicReference<>(ClientConnectionStatus.DISCONNECTED);
	}
	
	public void start() {
		resender.start();
	}
	
	public void stop() {
		resender.stop();
		setConnectionState(ClientConnectionStatus.DISCONNECTED);
	}
	
	public void restart() {
		resender.restart();
		setConnectionState(ClientConnectionStatus.DISCONNECTED);
	}
	
	public void setClientStatusCallback(ClientStatusCallback callback) {
		this.statusCallback = callback;
	}
	
	public void setClientToServerPacketCallback(ClientToServerPacketCallback callback) {
		this.packetCallback = callback;
	}
	
	public ClientConnectionStatus getStatus() {
		return status.get();
	}
	
	public void setTimeSinceLastPacket() {
		lastPacket.set(System.nanoTime());
	}
	
	public double getTimeSinceLastPacket() {
		return (System.nanoTime() - lastPacket.get()) / 1E6;
	}
	
	public void process(IncomingPacket packet) {
		process(packet, packet.getData());
	}
	
	private void process(IncomingPacket packet, byte [] raw) {
		if (raw.length < 2)
			return;
		ByteBuffer data = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
		if (data.get(0) != 0) {
			onSWGPacket(data.array());
			return;
		}
		Packet p = createPacket(data);
		if (p == null)
			return;
		if (!(p instanceof SessionRequest) && getStatus() == ClientConnectionStatus.DISCONNECTED) {
			Log.out(this, "Packet sent out of connection: %s", p);
			return;
		}
		handlePacket(packet, p);
	}
	
	private Packet createPacket(ByteBuffer data) {
		short opcode = data.getShort(0);
		switch (opcode) {
			case 0x01:	return new SessionRequest(data);
			case 0x03:	return new MultiPacket(data);
			case 0x05:	return new Disconnect(data);
			case 0x06:	return new KeepAlive(data);
			case 0x07:	return new ClientNetworkStatusUpdate(data);
			case 0x09:
			case 0x0A:
			case 0x0B:
			case 0x0C:	return new DataChannel(data);
			case 0x0D:
			case 0x0E:
			case 0x0F:
			case 0x10:	return new Fragmented(data);
			case 0x11:
			case 0x12:
			case 0x13:
			case 0x14:	return new OutOfOrder(data);
			case 0x15:
			case 0x16:
			case 0x17:
			case 0x18:	return new Acknowledge(data);
			default:
				Log.err(this, "Unknown SOE packet: %d  %s", opcode, ByteUtilities.getHexString(data.array()));
				return null;
		}
	}
	
	private void handlePacket(IncomingPacket incoming, Packet p) {
		if (p instanceof SessionRequest) {
			onSessionRequest(incoming, (SessionRequest) p);
			return;
		}
		if (incoming.getPort() != data.getCommunicationPort()) {
			Log.out(this, "Dropping packet on floor - not a valid port! Expected: %d  Actual: %d", data.getCommunicationPort(), incoming.getPort());
			return;
		}
		if (p instanceof MultiPacket)
			onMultiPacket(incoming, (MultiPacket) p);
		else if (p instanceof Disconnect)
			onDisconnect(incoming, (Disconnect) p);
		else if (p instanceof KeepAlive)
			onKeepAlive((KeepAlive) p);
		else if (p instanceof ClientNetworkStatusUpdate)
			onClientNetwork((ClientNetworkStatusUpdate) p);
		else if (p instanceof DataChannel)
			onDataChannel((DataChannel) p);
		else if (p instanceof Fragmented)
			onFragmented(incoming, (Fragmented) p);
		else if (p instanceof Acknowledge)
			onAcknowledge((Acknowledge) p);
		else if (p instanceof OutOfOrder)
			onOutOfOrder((OutOfOrder) p);
		else
			Log.err(this, "Unhandled SOE packet: %s", p);
	}
	
	private void onSessionRequest(IncomingPacket incoming, SessionRequest request) {
		if (data.getConnectionId() == request.getConnectionId() || data.getClientServer() == incoming.getServer()) {
			Log.out(this, "Dropping connection request! Has same ID or using same server");
			return;
		}
		switch (incoming.getServer()) {
			case LOGIN:
				Log.out(this, "Login Session Request [port set to %d]", incoming.getPort());
				setConnectionState(ClientConnectionStatus.LOGIN_CONNECTED);
				break;
			case ZONE:
				Log.out(this, "Zone Session Request [switching port from %d to %d]", data.getCommunicationPort(), incoming.getPort());
				setConnectionState(ClientConnectionStatus.ZONE_CONNECTED);
				break;
			default:
				Log.out(this, "Unknown server in session request! Server: %s", incoming.getServer());
				break;
		}
		data.reset();
		data.setConnectionId(request.getConnectionId());
		data.setCommunicationPort(incoming.getPort());
		data.setClientServer(incoming.getServer());
		packetSender.sendRaw(new SessionResponse(request.getConnectionId(), 0, (byte) 0, (byte) 0, (byte) 0, MAX_PACKET_SIZE));
		packetSender.sendPackaged(new ServerString("Holocore"), new ServerId(1));
	}
	
	private void onMultiPacket(IncomingPacket incoming, MultiPacket packet) {
		for (byte [] p : packet.getPackets()) {
			process(incoming, p);
		}
	}
	
	private void onDisconnect(IncomingPacket incoming, Disconnect disconnect) {
		Log.out(this, "Received client disconnect [port=%d reason=%s]", incoming.getPort(), disconnect.getReason());
		setConnectionState(ClientConnectionStatus.DISCONNECTED);
	}
	
	private void onKeepAlive(KeepAlive alive) {
		packetSender.sendRaw(new KeepAlive());
	}
	
	private void onClientNetwork(ClientNetworkStatusUpdate update) {
		ServerNetworkStatusUpdate serverNet = new ServerNetworkStatusUpdate();
		serverNet.setClientTickCount((short) update.getTick());
		serverNet.setServerSyncStampLong((int) (System.currentTimeMillis()-GALACTIC_BASE_TIME));
		serverNet.setClientPacketsSent(update.getSent());
		serverNet.setClientPacketsRecv(update.getRecv());
		serverNet.setServerPacketsSent(data.getTxSequence());
		serverNet.setServerPacketsRecv(data.getRxSequence()+1);
		packetSender.sendRaw(serverNet);
	}
	
	private boolean validateSequenced(SequencedPacket sequenced) {
		short rx = (short) (data.getRxSequence()+1);
		if (sequenced.getSequence() != rx) {
			if (sequenced.getSequence() > rx)
				packetSender.sendRaw(new OutOfOrder(sequenced.getSequence()));
			Log.err(this, "Invalid Sequence! Expected: " + rx + "  Actual: " + sequenced.getSequence());
			return false;
		}
		data.setRxSequence(sequenced.getSequence());
		packetSender.sendRaw(new Acknowledge(rx));
		return true;
	}
	
	private void onDataChannel(DataChannel dataChannel) {
		if (!validateSequenced(dataChannel))
			return;
		for (byte [] data : dataChannel.getPackets()) {
			onSWGPacket(data);
		}
	}
	
	private void onFragmented(IncomingPacket incoming, Fragmented frag) {
		if (!validateSequenced(frag))
			return;
		fragmentedBuffer.add(frag);
		frag = fragmentedBuffer.get(0);
		ByteBuffer data = frag.getPacketData();
		data.position(4);
		int size = Packet.getNetInt(data);
		int index = data.remaining();
		for (int i = 1; i < fragmentedBuffer.size() && index < size; i++)
			index += fragmentedBuffer.get(i).getPacketData().limit()-4;
		if (index == size)
			processFragmentedReady(incoming, size);
	}
	
	private void processFragmentedReady(IncomingPacket incoming, int size) {
		byte [] combined = new byte[size];
		int index = 0;
		while (index < combined.length) {
			ByteBuffer packet = fragmentedBuffer.get(0).getPacketData();
			packet.position(index == 0 ? 8 : 4);
			int len = packet.remaining();
			packet.get(combined, index, len);
			index += len;
			fragmentedBuffer.remove(0);
		}
		process(incoming, combined);
	}
	
	private void onAcknowledge(Acknowledge ack) {
		setTimeSinceLastPacket();
		data.setAckSequence(ack.getSequence());
		resender.clearTo(ack.getSequence());
	}
	
	private void onOutOfOrder(OutOfOrder ooo) {
		data.setOOOSequence(ooo.getSequence());
		resender.resendTo(ooo.getSequence());
	}
	
	private void onSWGPacket(byte [] data) {
		data = interceptor.interceptClient(data);
		packetCallback.onSWGPacket(data);
	}
	
	private void setConnectionState(ClientConnectionStatus status) {
		ClientConnectionStatus old = this.status.getAndSet(status);
		Log.out(this, "Client Status: %s -> %s", old, status);
		if (old != status && statusCallback != null) {
			statusCallback.onStatusChanged(old, status);
		}
	}
	
	public interface ClientToServerPacketCallback {
		void onSWGPacket(byte [] data);
	}
	
	public interface ClientStatusCallback {
		void onStatusChanged(ClientConnectionStatus oldStatus, ClientConnectionStatus newStatus);
	}
	
}
