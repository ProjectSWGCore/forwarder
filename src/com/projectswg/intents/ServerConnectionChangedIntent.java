package com.projectswg.intents;

import java.net.InetSocketAddress;

import com.projectswg.control.Intent;
import com.projectswg.resources.ServerConnectionStatus;

public class ServerConnectionChangedIntent extends Intent {
	
	public static final String TYPE = "ServerConnectionChangedIntent";
	
	private ServerConnectionStatus old;
	private ServerConnectionStatus status;
	private InetSocketAddress source;
	private InetSocketAddress destination;
	
	public ServerConnectionChangedIntent(ServerConnectionStatus old, ServerConnectionStatus status) {
		super(TYPE);
		setOldStatus(old);
		setStatus(status);
		setSource(null);
		setDestination(null);
	}
	
	public ServerConnectionStatus getStatus() {
		return status;
	}
	
	public void setStatus(ServerConnectionStatus status) {
		this.status = status;
	}
	
	public ServerConnectionStatus getOldStatus() {
		return old;
	}
	
	public void setOldStatus(ServerConnectionStatus old) {
		this.old = old;
	}
	
	public InetSocketAddress getSource() {
		return source;
	}
	
	public void setSource(InetSocketAddress source) {
		this.source = source;
	}
	
	public InetSocketAddress getDestination() {
		return destination;
	}
	
	public void setDestination(InetSocketAddress destination) {
		this.destination = destination;
	}
	
}
