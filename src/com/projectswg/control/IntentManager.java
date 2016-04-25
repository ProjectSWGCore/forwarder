/***********************************************************************************
* Copyright (c) 2015 /// Project SWG /// www.projectswg.com                        *
*                                                                                  *
* ProjectSWG is the first NGE emulator for Star Wars Galaxies founded on           *
* July 7th, 2011 after SOE announced the official shutdown of Star Wars Galaxies.  *
* Our goal is to create an emulator which will provide a server for players to     *
* continue playing a game similar to the one they used to play. We are basing      *
* it on the final publish of the game prior to end-game events.                    *
*                                                                                  *
* This file is part of Holocore.                                                   *
*                                                                                  *
* -------------------------------------------------------------------------------- *
*                                                                                  *
* Holocore is free software: you can redistribute it and/or modify                 *
* it under the terms of the GNU Affero General Public License as                   *
* published by the Free Software Foundation, either version 3 of the               *
* License, or (at your option) any later version.                                  *
*                                                                                  *
* Holocore is distributed in the hope that it will be useful,                      *
* but WITHOUT ANY WARRANTY; without even the implied warranty of                   *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                    *
* GNU Affero General Public License for more details.                              *
*                                                                                  *
* You should have received a copy of the GNU Affero General Public License         *
* along with Holocore.  If not, see <http://www.gnu.org/licenses/>.                *
*                                                                                  *
***********************************************************************************/
package com.projectswg.control;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import com.projectswg.utilities.Log;
import com.projectswg.utilities.ThreadUtilities;


public class IntentManager {
	
	private final Runnable broadcastRunnable;
	private final Map <String, Set<IntentReceiver>> intentRegistrations;
	private final Queue <Intent> intentQueue;
	private ExecutorService broadcastThreads;
	private boolean initialized = false;
	private boolean terminated = false;
	
	public IntentManager() {
		intentRegistrations = new HashMap<String, Set<IntentReceiver>>();
		intentQueue = new IntentQueue();
		initialize();
		broadcastRunnable = () -> {
			Intent i;
			synchronized (intentQueue) {
				i = intentQueue.poll();
			}
			if (i != null)
				broadcast(i);
		};
	}
	
	public void initialize() {
		if (!initialized) {
			broadcastThreads = Executors.newFixedThreadPool(3, ThreadUtilities.newThreadFactory("intent-manager-%d"));
			initialized = true;
			terminated = false;
		}
	}
	
	public void terminate() {
		if (!terminated) {
			initialized = false;
			terminated = true;
			broadcastThreads.shutdown();
		}
	}
	
	protected void broadcastIntent(Intent i) {
		if (i == null)
			throw new NullPointerException("Intent cannot be null!");
		if (!initialized)
			return;
		synchronized (intentQueue) {
			intentQueue.add(i);
		}
		try { broadcastThreads.execute(broadcastRunnable); }
		catch (RejectedExecutionException e) { } // This error is thrown when the server is being shut down
	}
	
	public void registerForIntent(String intentType, IntentReceiver r) {
		if (r == null)
			throw new NullPointerException("Cannot register a null value for an intent");
		synchronized (intentRegistrations) {
			Set <IntentReceiver> intents = intentRegistrations.get(intentType);
			if (intents == null) {
				intents = new CopyOnWriteArraySet<>();
				intentRegistrations.put(intentType, intents);
			}
			synchronized (intents) {
				intents.add(r);
			}
		}
	}
	
	protected void unregisterForIntent(String intentType, IntentReceiver r) {
		if (r == null)
			return;
		synchronized (intentRegistrations) {
			if (!intentRegistrations.containsKey(intentType))
				return;
			Set<IntentReceiver> receivers = intentRegistrations.get(intentType);
			for (IntentReceiver recv : receivers) {
				if (r == recv || r.equals(recv)) {
					r = recv;
					break;
				}
			}
			receivers.remove(r);
		}
	}
	
	private void broadcast(Intent i) {
		Set <IntentReceiver> receivers;
		synchronized (intentRegistrations) {
			receivers = intentRegistrations.get(i.getType());
		}
		if (receivers == null)
			return;
		for (IntentReceiver r : receivers) {
			broadcast(r, i);
		}
		i.markAsComplete(this);
	}
	
	private void broadcast(IntentReceiver r, Intent i) {
		try {
			r.onIntentReceived(i);
		} catch (Exception e) {
			Log.err(this, "Fatal Exception while processing intent: " + i);
			Log.err(this, e);
		}
	}
	
}
