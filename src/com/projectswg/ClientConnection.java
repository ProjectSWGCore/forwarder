package com.projectswg;

import network.packets.swg.SWGPacket;

import com.projectswg.control.Manager;
import com.projectswg.networking.NetInterceptor;
import com.projectswg.networking.Packet;
import com.projectswg.networking.NetInterceptor.InterceptorProperties;

public class ClientConnection extends Manager {
	
	private ClientSender sender;
	private ClientReceiver receiver;
	private NetInterceptor interceptor;
	
	public ClientConnection(int loginPort, boolean timeout) {
		interceptor = new NetInterceptor();
		sender = new ClientSender(interceptor, loginPort);
		receiver = new ClientReceiver(interceptor, timeout);
		receiver.setClientSender(sender);
		
		addChildService(sender);
		addChildService(receiver);
	}
	
	public InterceptorProperties getInterceptorProperties() {
		return interceptor.getProperties();
	}
	
	public boolean start() {
		if (!super.start())
			return false;
		interceptor.getProperties().setPort(sender.getZonePort());
		sender.setLoginCallback((packet) -> receiver.onPacket(false, packet));
		sender.setZoneCallback((packet) -> receiver.onPacket(true, packet));
		return true;
	}
	
	public void setLoginPort(int loginPort) {
		sender.setLoginPort(loginPort);
	}
	
	public void send(byte [] data) {
		sender.send(data);
	}
	
	public void send(Packet packet) {
		sender.send(packet);
	}
	
	public void send(SWGPacket packet) {
		sender.send(packet);
	}
	
	public int getLoginPort() {
		return sender.getLoginPort();
	}
	
	public int getZonePort() {
		return sender.getZonePort();
	}
	
}
