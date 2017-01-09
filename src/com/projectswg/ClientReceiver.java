package com.projectswg;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import network.packets.swg.login.ServerId;
import network.packets.swg.login.ServerString;
import network.packets.swg.zone.HeartBeat;

import com.projectswg.control.Intent;
import com.projectswg.control.Service;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ClientSonyPacketIntent;
import com.projectswg.intents.ClientToServerPacketIntent;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.Packet;
import com.projectswg.networking.UDPServer.UDPPacket;
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
import com.projectswg.networking.soe.Disconnect.DisconnectReason;
import com.projectswg.resources.ClientConnectionStatus;
import com.projectswg.utilities.ByteUtilities;
import com.projectswg.utilities.IntentChain;
import com.projectswg.utilities.Log;

public class ClientReceiver extends Service {
	
	private static final int MAX_PACKET_SIZE = 496;
	private static final int GALACTIC_BASE_TIME = 1323043200;

	private final NetInterceptor interceptor;
	private final AtomicLong lastPacket;
	private final List<Fragmented> fragmentedBuffer;
	private final IntentChain recvIntentChain;
	private final IntentChain stateIntentChain;
	private final boolean timeout;
	private ScheduledExecutorService pinger;
	private ExecutorService executor;
	private ClientSender sender;
	private ClientConnectionStatus status;
	private short rxSequence;
	private short ackSequence;
	private int port;
	private boolean zone;
	
	public ClientReceiver(NetInterceptor interceptor, boolean timeout) {
		this.interceptor = interceptor;
		this.lastPacket = new AtomicLong(0);
		this.fragmentedBuffer = new ArrayList<>();
		this.recvIntentChain = new IntentChain();
		this.stateIntentChain = new IntentChain();
		this.timeout = timeout;
		this.status = ClientConnectionStatus.DISCONNECTED;
		this.rxSequence = -1;
		this.ackSequence = -1;
	}
	
	@Override
	public boolean initialize() {
		registerForIntent(ClientSonyPacketIntent.TYPE);
		hardReset();
		return super.initialize();
	}
	
	@Override
	public boolean start() {
		executor = Executors.newSingleThreadExecutor();
		pinger = Executors.newSingleThreadScheduledExecutor();
		pinger.scheduleAtFixedRate(()->ping(), 0, 1000, TimeUnit.MILLISECONDS);
		reset();
		return super.start();
	}
	
	@Override
	public boolean stop() {
		if (executor != null)
			executor.shutdownNow();
		if (pinger != null)
			pinger.shutdownNow();
		try {
			if (executor != null)
				executor.awaitTermination(3, TimeUnit.SECONDS);
			if (pinger != null)
				pinger.awaitTermination(3, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Log.err(this, e);
		}
		setConnectionState(ClientConnectionStatus.DISCONNECTED);
		return super.stop();
	}
	
	@Override
	public void onIntentReceived(Intent i) {
		if (i instanceof ClientSonyPacketIntent) {
			Packet p = ((ClientSonyPacketIntent) i).getPacket();
			if (p instanceof SessionRequest)
				onSessionRequest((SessionRequest) p);
			else if (p instanceof MultiPacket)
				onMultiPacket((MultiPacket) p);
			else if (p instanceof Disconnect)
				onDisconnect((Disconnect) p);
			else if (p instanceof KeepAlive)
				onKeepAlive((KeepAlive) p);
			else if (p instanceof ClientNetworkStatusUpdate)
				onClientNetwork((ClientNetworkStatusUpdate) p);
			else if (p instanceof DataChannelA)
				onDataChannel((DataChannelA) p);
			else if (p instanceof Fragmented)
				onFragmented((Fragmented) p);
			else if (p instanceof Acknowledge)
				onAcknowledge((Acknowledge) p);
		}
	}
	
	public void setClientSender(ClientSender sender) {
		this.sender = sender;
	}
	
	public short getAckSequence() {
		return ackSequence;
	}
	
	public void reset() {
		rxSequence = -1;
	}
	
	public void hardReset() {
		setConnectionState(ClientConnectionStatus.DISCONNECTED);
		rxSequence = -1;
		lastPacket.set(0);
		fragmentedBuffer.clear();
		recvIntentChain.reset();
		stateIntentChain.reset();
		port = 0;
		zone = false;
	}
	
	public double getTimeSinceLastPacket() {
		return (System.nanoTime() - lastPacket.get()) / 1E6;
	}
	
	public void onPacket(boolean zone, UDPPacket packet) {
		if (packet.getData().length < 2)
			return;
		lastPacket.set(System.nanoTime());
		if (zone && packet.getData().length == 4) { // Ping
			sender.sendRaw(packet.getPort(), packet.getData());
			return;
		}
		if (packet.getData()[0] == 0 && packet.getData()[1] == 1) {
			this.zone = zone;
			this.port = packet.getPort();
			reset();
			sender.reset();
			sender.setZone(zone);
			sender.setPort(packet.getPort());
		} else if (packet.getPort() != port)
			return;
		executor.execute(() -> process(ByteBuffer.wrap(packet.getData()).order(ByteOrder.BIG_ENDIAN)));
	}
	
	private void ping() {
		if (status == ClientConnectionStatus.DISCONNECTED || !timeout)
			return;
		if (getTimeSinceLastPacket() > 5000 && !interceptor.getData().isZoning())
			setConnectionState(ClientConnectionStatus.DISCONNECTED);
		else if (getTimeSinceLastPacket() > 30000 && interceptor.getData().isZoning())
			setConnectionState(ClientConnectionStatus.DISCONNECTED);
		else
			sender.send(new HeartBeat().encode().array());
	}
	
	private void setConnectionState(ClientConnectionStatus state) {
		if (this.status != state) {
			stateIntentChain.broadcastAfter(new ClientConnectionChangedIntent(this.status, state), getIntentManager());
			this.status = state;
		}
	}
	
	private void process(ByteBuffer data) {
		if (data.array().length < 2) {
			return;
		}
		if (data.get(0) != 0) {
			onSWGPacket(data.array());
			return;
		}
		Packet p = null;
		switch (data.order(ByteOrder.BIG_ENDIAN).getShort(0)) {
			case 0x01:	p = new SessionRequest(data); break;
			case 0x03:	p = new MultiPacket(data); break;
			case 0x05:	p = new Disconnect(data); break;
			case 0x06:	p = new KeepAlive(data); break;
			case 0x07:	p = new ClientNetworkStatusUpdate(data); break;
			case 0x09:	p = new DataChannelA(data); break;
			case 0x0D:	p = new Fragmented(data); break;
			case 0x11:	p = new OutOfOrder(data); break;
			case 0x15:	p = new Acknowledge(data); break;
			default:
				Log.out(this, "onPacket(addr=localhost:"+port+", type="+data.getShort(0)+", "+ByteUtilities.getHexString(data.array())+")");
				break;
		}
		if (p != null)
			recvIntentChain.broadcastAfter(new ClientSonyPacketIntent(p), getIntentManager());
	}
	
	private void onSessionRequest(SessionRequest request) {
		if (zone) {
			Log.out(this, "Zone Session Request");
			setConnectionState(ClientConnectionStatus.ZONE_CONNECTED);
		} else {
			Log.out(this, "Login Session Request");
			setConnectionState(ClientConnectionStatus.LOGIN_CONNECTED);
		}
		int oldId = sender.getConnectionId();
		sender.setConnectionId(request.getConnectionID());
		sender.send(new SessionResponse(request.getConnectionID(), 0, (byte) 0, (byte) 0, (byte) 0, MAX_PACKET_SIZE));
		sender.send(new ServerString("Holocore"), new ServerId(1));
		if (oldId != -1)
			sender.send(new Disconnect(oldId, DisconnectReason.APPLICATION));
	}
	
	private void onMultiPacket(MultiPacket packet) {
		for (byte [] p : packet.getPackets()) {
			process(ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN));
		}
	}
	
	private void onDisconnect(Disconnect disconnect) {
		Log.out(this, "Received client disconnect");
		setConnectionState(ClientConnectionStatus.DISCONNECTED);
		zone = false;
	}
	
	private void onKeepAlive(KeepAlive alive) {
		sender.send(new KeepAlive());
	}
	
	private void onClientNetwork(ClientNetworkStatusUpdate update) {
		ServerNetworkStatusUpdate serverNet = new ServerNetworkStatusUpdate();
		serverNet.setClientTickCount((short) update.getTick());
		serverNet.setServerSyncStampLong((int) (System.currentTimeMillis()-GALACTIC_BASE_TIME));
		serverNet.setClientPacketsSent(update.getSent());
		serverNet.setClientPacketsRecv(update.getRecv());
		serverNet.setServerPacketsSent(sender.getSequence());
		serverNet.setServerPacketsRecv(rxSequence+1);
		sender.send(serverNet);
	}
	
	private void onDataChannel(DataChannelA dataChannel) {
		if (dataChannel.getSequence() != (short)(rxSequence+1)) {
			if (dataChannel.getSequence() > rxSequence)
				sender.send(new OutOfOrder(dataChannel.getSequence()));
			Log.err(this, "Invalid Sequence! Expected: " + (rxSequence+1) + "  Actual: " + dataChannel.getSequence());
			return;
		}
		rxSequence = dataChannel.getSequence();
		sender.send(new Acknowledge(rxSequence));
		for (byte [] data : dataChannel.getPackets()) {
			onSWGPacket(data);
		}
	}
	
	private void onFragmented(Fragmented frag) {
		if (frag.getSequence() != (short)(rxSequence+1)) {
			if (frag.getSequence() > rxSequence)
				sender.send(new OutOfOrder(frag.getSequence()));
			Log.err(this, "Invalid Sequence! Expected: " + (rxSequence+1) + "  Actual: " + frag.getSequence());
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
	
	private void onAcknowledge(Acknowledge ack) {
		this.ackSequence = ack.getSequence();
	}
	
	private void onSWGPacket(byte [] data) {
		data = interceptor.interceptClient(data);
		recvIntentChain.broadcastAfter(new ClientToServerPacketIntent(data), getIntentManager());
	}
	
}
