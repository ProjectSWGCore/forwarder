package com.projectswg.utilities;

import com.projectswg.control.Intent;
import com.projectswg.control.IntentManager;

public class IntentChain {
	
	private final Object mutex;
	private Intent i;
	
	public IntentChain() {
		mutex = new Object();
		i = null;
	}
	
	public void reset() {
		synchronized (mutex) {
			i = null;
		}
	}
	
	public void broadcastAfter(Intent i, IntentManager intentManager) {
		synchronized (mutex) {
			i.broadcastAfterIntent(this.i, intentManager);
			this.i = i;
		}
	}
	
}
