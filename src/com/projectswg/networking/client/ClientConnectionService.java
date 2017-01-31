package com.projectswg.networking.client;

import network.packets.swg.SWGPacket;
import network.packets.swg.zone.HeartBeat;

import com.projectswg.concurrency.PswgBasicScheduledThread;
import com.projectswg.concurrency.PswgTaskThreadPool;
import com.projectswg.control.Service;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ClientToServerPacketIntent;
import com.projectswg.intents.ServerToClientPacketIntent;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.Packet;
import com.projectswg.networking.NetInterceptor.InterceptorProperties;
import com.projectswg.networking.client.ClientServerSocket.IncomingPacket;
import com.projectswg.networking.client.sender.PacketPackager;
import com.projectswg.networking.soe.Disconnect;
import com.projectswg.networking.soe.Disconnect.DisconnectReason;
import com.projectswg.resources.ClientConnectionStatus;
import com.projectswg.utilities.IntentChain;
import com.projectswg.utilities.ThreadUtilities;

public class ClientConnectionService extends Service implements ClientPacketSender {
	
	private final NetInterceptor interceptor;
	private final ClientData data;
	private IntentChain intentChain;
	/* Send-related variables */
	private final PacketPackager packager;
	private final ClientServerSocket server;
	/* Receive-related variables */
	private final PswgTaskThreadPool<IncomingPacket> processorThreadPool;
	private final PswgBasicScheduledThread pingerThread;
	private final ClientProcessor packetProcessor;
	
	public ClientConnectionService(int loginPort, boolean timeout) {
		this.interceptor = new NetInterceptor();
		this.data = new ClientData();
		this.packager = new PacketPackager(data, interceptor, this);
		this.server = new ClientServerSocket(data, loginPort);
		this.packetProcessor = new ClientProcessor(interceptor, data, this);
		this.processorThreadPool = new PswgTaskThreadPool<>(1, "packet-processor", (packet) -> processPacket(packet));
		this.pingerThread = new PswgBasicScheduledThread("client-pinger", () -> ping(timeout));
	}
	
	public InterceptorProperties getInterceptorProperties() {
		return interceptor.getProperties();
	}
	
	@Override
	public boolean initialize() {
		intentChain = new IntentChain(getIntentManager());
		registerForIntent(ServerToClientPacketIntent.class, stcpi -> handleServerToClientPacketIntent(stcpi));
		return super.initialize();
	}
	
	@Override
	public boolean start() {
		data.reset();
		if (!server.connect(incoming -> onPacket(incoming)))
			return false;
		interceptor.getProperties().setPort(server.getZonePort());
		packager.start();
		packetProcessor.setClientStatusCallback((oldStatus, newStatus) -> intentChain.broadcastAfter(new ClientConnectionChangedIntent(oldStatus, newStatus)));
		packetProcessor.setClientToServerPacketCallback(packet -> intentChain.broadcastAfter(new ClientToServerPacketIntent(packet)));
		packetProcessor.start();
		processorThreadPool.start();
		pingerThread.startWithFixedRate(0, 1000);
		return super.start();
	}
	
	@Override
	public boolean stop() {
		packetProcessor.stop();
		processorThreadPool.stop();
		pingerThread.stop();
		processorThreadPool.awaitTermination(3000);
		pingerThread.awaitTermination(3000);
		packager.stop();
		disconnect(DisconnectReason.APPLICATION);
		server.disconnect();
		data.reset();
		return super.stop();
	}
	
	public void restart() {
		packetProcessor.restart();
		packager.restart();
		data.reset();
	}
	
	public boolean isConnected() {
		return server.isConnected();
	}
	
	public void waitForClientAcknowledge() {
		while (data.getTxSequence()-1 > data.getAckSequence() && data.getConnectionId() != -1) {
			if (!ThreadUtilities.sleep(1))
				break;
		}
	}
	
	public void disconnect(DisconnectReason reason) {
		if (data.getConnectionId() != -1)
			sendRaw(new Disconnect(data.getConnectionId(), reason));
	}
	
	public void sendPackaged(byte [] ... packets) {
		if (data.getCommunicationPort() <= 0)
			return;
		for (byte [] data : packets)
			packager.addToPackage(data);
	}
	
	public void sendPackaged(SWGPacket ... packets) {
		if (data.getCommunicationPort() <= 0)
			return;
		for (SWGPacket p : packets)
			sendPackaged(p.encode().array());
	}
	
	public void sendRaw(byte [] ... packets) {
		if (data.getCommunicationPort() <= 0)
			return;
		for (byte [] data : packets)
			server.send(data);
	}
	
	public void sendRaw(Packet ... packets) {
		if (data.getCommunicationPort() <= 0)
			return;
		for (Packet p : packets)
			sendRaw(p.encode().array());
	}
	
	public int getLoginPort() {
		return server.getLoginPort();
	}
	
	public int getZonePort() {
		return server.getZonePort();
	}
	
	private void handleServerToClientPacketIntent(ServerToClientPacketIntent stcpi) {
		sendPackaged(stcpi.getRawData());
	}
	
	private void processPacket(IncomingPacket incoming) {
		packetProcessor.process(incoming);
	}
	
	private void onPacket(IncomingPacket incoming) {
		if (!processorThreadPool.isRunning())
			return;
		if (incoming.getLength() == 4 && incoming.getData()[0] != 0) { // Reasonable to assume this is a ping packet
			server.send(incoming.getPacket());
			return;
		}
		processorThreadPool.addTask(incoming);
	}
	
	private void ping(boolean timeout) {
		if (packetProcessor.getStatus() == ClientConnectionStatus.DISCONNECTED || !timeout)
			return;
		boolean disconnect = packetProcessor.getTimeSinceLastPacket() > 5000 && !interceptor.getData().isZoning();
		disconnect |= packetProcessor.getTimeSinceLastPacket() > 30000 && interceptor.getData().isZoning();
		if (disconnect) {
			disconnect(DisconnectReason.TIMEOUT);
			restart();
		} else
			sendPackaged(new HeartBeat().encode().array());
	}
	
}
