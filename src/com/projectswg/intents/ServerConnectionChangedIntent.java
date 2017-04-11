package com.projectswg.intents;

import com.projectswg.common.control.Intent;
import com.projectswg.connection.ServerConnectionChangedReason;
import com.projectswg.connection.ServerConnectionStatus;

public class ServerConnectionChangedIntent extends Intent {
	
	private ServerConnectionStatus old;
	private ServerConnectionStatus status;
	private ServerConnectionChangedReason reason;
	
	public ServerConnectionChangedIntent(ServerConnectionStatus old, ServerConnectionStatus status, ServerConnectionChangedReason reason) {
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
