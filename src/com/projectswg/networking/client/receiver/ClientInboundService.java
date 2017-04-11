package com.projectswg.networking.client.receiver;

import com.projectswg.common.control.Service;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.client.ClientData;
import com.projectswg.networking.client.ClientPacketSender;
import com.projectswg.networking.client.ClientServerSocket.IncomingPacket;
import com.projectswg.resources.ClientConnectionStatus;

public class ClientInboundService extends Service {
	
	private final ClientInboundProcessor processor;
	
	public ClientInboundService(NetInterceptor interceptor, ClientData data, ClientPacketSender packetSender) {
		this.processor = new ClientInboundProcessor(interceptor, data, packetSender);
	}
	
	@Override
	public boolean initialize() {
		registerForIntent(ClientConnectionChangedIntent.class, ccci -> onConnectionChanged(ccci.getStatus()));
		return super.initialize();
	}
	
	@Override
	public boolean start() {
		processor.start(getIntentManager());
		return super.start();
	}
	
	@Override
	public boolean stop() {
		processor.stop();
		return super.stop();
	}
	
	public void addPacket(IncomingPacket packet) {
		processor.addPacket(packet);
	}
	
	public void disconnect() {
		processor.disconnect();
	}
	
	private void onConnectionChanged(ClientConnectionStatus status) {
		switch (status) {
			case LOGIN_CONNECTED:
			case ZONE_CONNECTED:
				processor.onConnected();
				break;
			case DISCONNECTED:
				processor.onDisconnected();
				break;
		}
	}
	
}
