package com.projectswg.networking.client.sender;

import com.projectswg.common.control.Service;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.client.ClientData;
import com.projectswg.networking.client.ClientPacketSender;

public class ClientOutboundService extends Service {
	
	private final ClientPackager packager;
	
	public ClientOutboundService(ClientData data, NetInterceptor interceptor, ClientPacketSender sender) {
		this.packager = new ClientPackager(interceptor, data, sender);
	}
	
	@Override
	public boolean start() {
		packager.start(getIntentManager());
		return super.start();
	}
	
	@Override
	public boolean stop() {
		packager.stop();
		return super.stop();
	}
	
	public void sendSequential(byte [] data) {
		packager.addToPackage(data);
	}
	
}
