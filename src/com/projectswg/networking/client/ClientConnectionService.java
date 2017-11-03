package com.projectswg.networking.client;

import com.projectswg.common.concurrency.Delay;
import com.projectswg.common.concurrency.PswgBasicScheduledThread;
import com.projectswg.common.control.Manager;
import com.projectswg.common.debug.Log;
import com.projectswg.intents.ServerToClientPacketIntent;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.NetInterceptor.InterceptorProperties;
import com.projectswg.networking.Packet;
import com.projectswg.networking.client.ClientServerSocket.IncomingPacket;
import com.projectswg.networking.client.receiver.ClientInboundService;
import com.projectswg.networking.client.sender.ClientOutboundService;
import com.projectswg.networking.soe.Disconnect;
import com.projectswg.networking.soe.Disconnect.DisconnectReason;
import com.projectswg.resources.ClientConnectionStatus;

import network.packets.swg.SWGPacket;
import com.projectswg.common.network.packets.swg.zone.HeartBeat;

public class ClientConnectionService extends Manager implements ClientPacketSender {
	
	private final NetInterceptor interceptor;
	private final ClientData clientData;
	/* Send-related variables */
	private final ClientOutboundService outboundService;
	private final ClientServerSocket server;
	/* Receive-related variables */
	private final PswgBasicScheduledThread pingerThread;
	private final ClientInboundService inboundService;
	
	public ClientConnectionService(int initialLoginPort, boolean timeout) {
		this.clientData = new ClientData();
		this.interceptor = new NetInterceptor(clientData);
		this.server = new ClientServerSocket(clientData, initialLoginPort);
		this.outboundService = new ClientOutboundService(clientData, interceptor, this);
		this.inboundService = new ClientInboundService(interceptor, clientData, this);
		this.pingerThread = new PswgBasicScheduledThread("client-pinger", () -> ping(timeout));
		
		addChildService(outboundService);
		addChildService(inboundService);
	}
	
	public InterceptorProperties getInterceptorProperties() {
		return interceptor.getProperties();
	}
	
	@Override
	public boolean initialize() {
		registerForIntent(ServerToClientPacketIntent.class, stcpi -> handleServerToClientPacketIntent(stcpi));
		return super.initialize();
	}
	
	@Override
	public boolean start() {
		if (!server.connect(incoming -> onPacket(incoming)))
			return false;
		interceptor.getProperties().setPort(server.getZonePort());
		pingerThread.startWithFixedRate(0, 1000);
		return super.start();
	}
	
	@Override
	public boolean stop() {
		pingerThread.stop();
		pingerThread.awaitTermination(3000);
		server.disconnect();
		return super.stop();
	}
	
	public boolean isConnected() {
		return server.isConnected();
	}
	
	public void waitForClientAcknowledge() {
		while (clientData.isWaitingForClientAcknowledge()) {
			if (Delay.sleepMicro(5))
				break;
		}
	}
	
	public void disconnect(DisconnectReason reason) {
		if (clientData.getConnectionId() != -1)
			sendRaw(new Disconnect(clientData.getConnectionId(), reason));
		inboundService.disconnect();
	}
	
	public int getLoginPort() {
		return server.getLoginPort();
	}
	
	public int getZonePort() {
		return server.getZonePort();
	}
	
	@Override
	public void sendPackaged(byte[] ... packets) {
		if (!clientData.isConnectionInitialized())
			return;
		for (byte[] data : packets)
			outboundService.sendSequential(data);
	}
	
	@Override
	public void sendPackaged(SWGPacket ... packets) {
		if (!clientData.isConnectionInitialized())
			return;
		for (SWGPacket p : packets)
			outboundService.sendSequential(p.encode().array());
	}
	
	@Override
	public void sendRaw(byte[]... packets) {
		if (!clientData.isConnectionInitialized())
			return;
		for (byte [] data : packets)
			server.send(data);
	}
	
	@Override
	public void sendRaw(Packet... packets) {
		if (!clientData.isConnectionInitialized())
			return;
		for (Packet p : packets)
			server.send(p.encode().array());
	}
	
	private void handleServerToClientPacketIntent(ServerToClientPacketIntent stcpi) {
		sendPackaged(stcpi.getRawData());
	}
	
	private void onPacket(IncomingPacket incoming) {
		if (incoming.getLength() == 4 && incoming.getData()[0] != 0) { // Reasonable to assume this is a ping packet
			server.send(incoming.getPacket());
			return;
		}
		inboundService.addPacket(incoming);
	}
	
	private void ping(boolean timeout) {
		if (clientData.getStatus() == ClientConnectionStatus.DISCONNECTED || !timeout)
			return;
		if (clientData.isTimedOut()) {
			Log.i("Disconnecting due to timeout!  Time Since Packet: %.2fms  Zoning: %b", clientData.getTimeSinceLastAcknowledgement(), clientData.isZoning());
			disconnect(DisconnectReason.TIMEOUT);
		} else {
			sendPackaged(new HeartBeat().encode().array());
		}
	}
	
}
