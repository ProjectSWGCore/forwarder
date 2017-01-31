package com.projectswg.intents;

import com.projectswg.control.Intent;
import com.projectswg.networking.server.ServerConnectionChangedReason;
import com.projectswg.resources.ServerConnectionStatus;

public class ServerConnectionChangedIntent extends Intent {
	
	public static final String TYPE = "ServerConnectionChangedIntent";
	
	private ServerConnectionStatus old;
	private ServerConnectionStatus status;
	private ServerConnectionChangedReason reason;
	
	public ServerConnectionChangedIntent(ServerConnectionStatus old, ServerConnectionStatus status, ServerConnectionChangedReason reason) {
		super(TYPE);
		setOldStatus(old);
		setStatus(status);
		setReason(reason);
	}
	
	public ServerConnectionStatus getStatus() {
		return status;
	}
	
	public ServerConnectionStatus getOldStatus() {
		return old;
	}
	
	public ServerConnectionChangedReason getReason() {
		return reason;
	}
	
	public void setStatus(ServerConnectionStatus status) {
		this.status = status;
	}
	
	public void setOldStatus(ServerConnectionStatus old) {
		this.old = old;
	}
	
	public void setReason(ServerConnectionChangedReason reason) {
		this.reason = reason;
	}
	
}
