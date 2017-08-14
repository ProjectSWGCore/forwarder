package com.projectswg.networking.client.receiver;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.projectswg.common.concurrency.PswgTaskThreadPool;
import com.projectswg.common.control.IntentChain;
import com.projectswg.common.control.IntentManager;
import com.projectswg.common.debug.Log;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ClientSonyPacketIntent;
import com.projectswg.intents.ClientToServerPacketIntent;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.Packet;
import com.projectswg.networking.client.ClientData;
import com.projectswg.networking.client.ClientPacketSender;
import com.projectswg.networking.client.ClientServerSocket.ClientServer;
import com.projectswg.networking.client.ClientServerSocket.IncomingPacket;
import com.projectswg.networking.soe.Acknowledge;
import com.projectswg.networking.soe.ClientNetworkStatusUpdate;
import com.projectswg.networking.soe.DataChannel;
import com.projectswg.networking.soe.Disconnect;
import com.projectswg.networking.soe.Disconnect.DisconnectReason;
import com.projectswg.networking.soe.Fragmented;
import com.projectswg.networking.soe.KeepAlive;
import com.projectswg.networking.soe.MultiPacket;
import com.projectswg.networking.soe.OutOfOrder;
import com.projectswg.networking.soe.SequencedPacket;
import com.projectswg.networking.soe.ServerNetworkStatusUpdate;
import com.projectswg.networking.soe.SessionRequest;
import com.projectswg.networking.soe.SessionResponse;
import com.projectswg.resources.ClientConnectionStatus;
import com.projectswg.utilities.ByteUtilities;

public class ClientInboundProcessor {
	
	private static final int MAX_PACKET_SIZE	= 496;
	private static final int GALACTIC_BASE_TIME	= 1323043200;
	
	private final NetInterceptor interceptor;
	private final ClientData clientData;
	private final ClientFragmentedProcessor fragmentedProcessor;
	private final PswgTaskThreadPool<IncomingPacket> processorThreadPool;
	private final ClientPacketSender packetSender;
	
	private IntentChain processorIntentChain;
	
	public ClientInboundProcessor(NetInterceptor interceptor, ClientData data, ClientPacketSender packetSender) {
		this.interceptor = interceptor;
		this.clientData = data;
		this.fragmentedProcessor = new ClientFragmentedProcessor();
		this.processorThreadPool = new PswgTaskThreadPool<>(1, "packet-processor", packet -> process(packet, packet.getData()));
		this.packetSender = packetSender;
		this.processorIntentChain = null;
	}
	
	public void start(IntentManager intentManager) {
		processorIntentChain = new IntentChain(intentManager);
		processorThreadPool.start();
	}
	
	public void stop() {
		processorThreadPool.stop(false);
		processorThreadPool.awaitTermination(1000);
	}
	
	public void addPacket(IncomingPacket packet) {
		processorThreadPool.addTask(packet);
	}
	
	public void disconnect() {
		setConnectionState(ClientConnectionStatus.DISCONNECTED);
	}
	
	public void onConnected() {
		fragmentedProcessor.reset();
	}
	
	public void onDisconnected() {
		setConnectionState(ClientConnectionStatus.DISCONNECTED);
		clientData.reset(ClientConnectionStatus.DISCONNECTED);
		fragmentedProcessor.reset();
	}
	
	private void process(IncomingPacket packet, byte [] raw) {
		if (raw.length < 2)
			return;
		ByteBuffer data = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
		if (data.get(0) != 0) {
			onSWGPacket(data.array());
			return;
		}
		Packet p;
		try {
			p = createPacket(data);
			if (p == null)
				return;
		} catch (BufferUnderflowException e) {
			Log.w("Invalid packet structure received! %s", ByteUtilities.getHexString(raw));
			return;
		}
		if (!(p instanceof SessionRequest) && clientData.getStatus() == ClientConnectionStatus.DISCONNECTED) {
			Log.i("Packet sent out of connection: %s", p);
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
				Log.e("Unknown SOE packet: %d  %s", opcode, ByteUtilities.getHexString(data.array()));
				return null;
		}
	}
	
	private void handlePacket(IncomingPacket incoming, Packet p) {
		if (p instanceof SessionRequest) {
			onSessionRequest(incoming, (SessionRequest) p);
			processorIntentChain.broadcastAfter(new ClientSonyPacketIntent(p));
			return;
		}
		if (incoming.getServer() != clientData.getClientServer()) {
			Log.w("Dropping packet %s with invalid server %s [expected %s]", p, incoming.getServer(), clientData.getClientServer());
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
			Log.e("Unhandled SOE packet: %s", p);
		processorIntentChain.broadcastAfter(new ClientSonyPacketIntent(p));
	}
	
	private void onSessionRequest(IncomingPacket incoming, SessionRequest request) {
		ClientConnectionStatus newStatus;
		switch (incoming.getServer()) {
			case LOGIN:
				Log.i("Login Session Request [port set to %d]", incoming.getPort());
				newStatus = ClientConnectionStatus.LOGIN_CONNECTED;
				break;
			case ZONE:
				Log.i("Zone Session Request [switching port from %d to %d]", clientData.getCommunicationPort(), incoming.getPort());
				newStatus = ClientConnectionStatus.ZONE_CONNECTED;
				break;
			default:
				Log.i("Unknown server in session request! Server: %s", incoming.getServer());
				return;
		}
		clientData.resetConnectionInfo();
		clientData.setClientServer(incoming.getServer());
		if (incoming.getServer() == ClientServer.ZONE)
			packetSender.sendRaw(new Disconnect(clientData.getConnectionId(), DisconnectReason.NEW_CONNECTION_ATTEMPT));
		clientData.setConnectionId(request.getConnectionId());
		clientData.setCommunicationPort(incoming.getPort());
		packetSender.sendRaw(new SessionResponse(request.getConnectionId(), 0, (byte) 0, (byte) 0, (byte) 0, MAX_PACKET_SIZE));
		setConnectionState(newStatus);
	}
	
	private void onMultiPacket(IncomingPacket incoming, MultiPacket packet) {
		for (byte [] p : packet.getPackets()) {
			process(incoming, p);
		}
	}
	
	private void onDisconnect(IncomingPacket incoming, Disconnect disconnect) {
		if (disconnect.getConnectionId() != clientData.getConnectionId()) {
			Log.w("Ignoring old disconnect! Current ID: %d  Disconnect ID: %d", clientData.getConnectionId(), disconnect.getConnectionId());
			return;
		}
		Log.i("Received client disconnect [port=%d reason=%s]", incoming.getPort(), disconnect.getReason());
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
		serverNet.setServerPacketsSent(clientData.getTxSequence());
		serverNet.setServerPacketsRecv(clientData.getRxSequence()+1);
		packetSender.sendRaw(serverNet);
	}
	
	private boolean validateSequenced(SequencedPacket sequenced) {
		short rx = (short) (clientData.getRxSequence()+1);
		if (sequenced.getSequence() != rx) {
			if (sequenced.getSequence() > rx)
				packetSender.sendRaw(new OutOfOrder(sequenced.getSequence()));
			Log.e("Invalid Sequence! Expected: " + rx + "  Actual: " + sequenced.getSequence());
			return false;
		}
		clientData.setRxSequence(sequenced.getSequence());
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
		byte [] combined = fragmentedProcessor.addFragmented(frag);
		if (combined != null)
			process(incoming, combined);
	}
	
	private void onAcknowledge(Acknowledge ack) {
		clientData.setLastAcknowledgement(System.nanoTime());
		clientData.setAckSequence(ack.getSequence());
	}
	
	private void onOutOfOrder(OutOfOrder ooo) {
		clientData.setOOOSequence(ooo.getSequence());
	}
	
	private void onSWGPacket(byte [] data) {
		data = interceptor.interceptClient(data);
		processorIntentChain.broadcastAfter(new ClientToServerPacketIntent(data));
	}
	
	private void setConnectionState(ClientConnectionStatus status) {
		ClientConnectionStatus old = clientData.setStatus(status);
		if (old != status) {
			Log.i("Client Status: %s -> %s", old, status);
			processorIntentChain.broadcastAfter(new ClientConnectionChangedIntent(old, status));
		}
	}
	
}
