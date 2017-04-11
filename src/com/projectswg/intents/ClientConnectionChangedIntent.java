package com.projectswg.intents;

import com.projectswg.common.control.Intent;
import com.projectswg.resources.ClientConnectionStatus;

public class ClientConnectionChangedIntent extends Intent {
	
	private ClientConnectionStatus old;
	private ClientConnectionStatus status;
	
	public ClientConnectionChangedIntent(ClientConnectionStatus old, ClientConnectionStatus status) {
		setOldStatus(old);
		setStatus(status);
	}
	
	public ClientConnectionStatus getStatus() {
		return status;
	}
	
	public void setStatus(ClientConnectionStatus status) {
		this.status = status;
	}
	
	public ClientConnectionStatus getOldStatus() {
		return old;
	}
	
	public void setOldStatus(ClientConnectionStatus old) {
		this.old = old;
	}
	
}
