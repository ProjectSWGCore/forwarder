package com.projectswg.utilities;

import com.projectswg.control.Intent;
import com.projectswg.control.IntentManager;

public class IntentChain {
	
	private final IntentManager intentManager;
	private final Object mutex;
	private Intent i;
	
	public IntentChain(IntentManager intentManager) {
		this.intentManager = intentManager;
		this.mutex = new Object();
		this.i = null;
	}
	
	public void reset() {
		synchronized (mutex) {
			i = null;
		}
	}
	
	public void broadcastAfter(Intent i) {
		synchronized (mutex) {
			i.broadcastAfterIntent(this.i, intentManager);
			this.i = i;
		}
	}
	
}
