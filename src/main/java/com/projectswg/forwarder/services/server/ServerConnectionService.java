package com.projectswg.forwarder.services.server;

import com.projectswg.common.utilities.ByteUtilities;
import com.projectswg.connection.HolocoreSocket;
import com.projectswg.connection.RawPacket;
import com.projectswg.connection.ServerConnectionChangedReason;
import com.projectswg.forwarder.Forwarder.ForwarderData;
import com.projectswg.forwarder.intents.client.ClientConnectedIntent;
import com.projectswg.forwarder.intents.client.ClientDisconnectedIntent;
import com.projectswg.forwarder.intents.client.DataPacketInboundIntent;
import com.projectswg.forwarder.intents.client.DataPacketOutboundIntent;
import com.projectswg.forwarder.intents.control.StartForwarderIntent;
import com.projectswg.forwarder.intents.control.StopForwarderIntent;
import com.projectswg.forwarder.intents.server.ServerConnectedIntent;
import com.projectswg.forwarder.intents.server.ServerDisconnectedIntent;
import com.projectswg.forwarder.resources.networking.NetInterceptor;
import me.joshlarson.jlcommon.concurrency.Delay;
import me.joshlarson.jlcommon.concurrency.ThreadPool;
import me.joshlarson.jlcommon.control.IntentChain;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.Service;
import me.joshlarson.jlcommon.log.Log;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ServerConnectionService extends Service {
	
	private final IntentChain intentChain;
	private final AtomicReference<Thread> activeThread;
	private final AtomicBoolean running;
	private final ThreadPool thread;
	private final Lock runLock;
	private final Lock sleepLock;
	
	private HolocoreSocket holocore;
	private NetInterceptor interceptor;
	private ForwarderData data;
	
	public ServerConnectionService() {
		this.intentChain = new IntentChain();
		this.activeThread = new AtomicReference<>(null);
		this.running = new AtomicBoolean(false);
		this.thread = new ThreadPool(2, "server-connection");
		this.runLock = new ReentrantLock(true);
		this.sleepLock = new ReentrantLock(true);
		this.holocore = null;
		this.interceptor = null;
		this.data = null;
	}
	
	@Override
	public boolean start() {
		thread.start();
		return true;
	}
	
	@Override
	public boolean stop() {
		running.set(false);
		thread.stop(true);
		return thread.awaitTermination(1000);
	}
	
	@IntentHandler
	private void handleStartForwarderIntent(StartForwarderIntent sfi) {
		interceptor = new NetInterceptor(sfi.getData());
		data = sfi.getData();
	}
	
	@IntentHandler
	private void handleStopForwarderIntent(StopForwarderIntent sfi) {
		stopRunningLoop();
	}
	
	@IntentHandler
	private void handleClientConnectedIntent(ClientConnectedIntent cci) {
		queueConnectionLoop(0);
	}
	
	@IntentHandler
	private void handleClientDisconnectedIntent(ClientDisconnectedIntent cdi) {
		stopRunningLoop();
	}
	
	@IntentHandler
	private void handleDataPacketInboundIntent(DataPacketInboundIntent dpii) {
		if (running.get())
			holocore.send(interceptor.interceptClient(dpii.getData()));
		else
			Log.w("Dropping packet destined for server: %s", ByteUtilities.getHexString(dpii.getData()));
	}
	
	private void stopRunningLoop() {
		running.set(false);
		Thread activeThread = this.activeThread.get();
		if (activeThread != null)
			activeThread.interrupt();
		try {
			if (runLock.tryLock(1, TimeUnit.SECONDS))
				runLock.unlock();
		} catch (InterruptedException e) {
			// Ignored
		}
	}
	
	private void queueConnectionLoop(long delay) {
		thread.execute(() -> startConnectionLoop(delay));
	}
	
	private void startConnectionLoop(long delay) {
		if (sleepLock.tryLock()) {
			try {
				if (!Delay.sleepMilli(delay))
					return;
			} finally {
				sleepLock.unlock();
			}
		} else if (delay > 0) {
			return;
		}
		if (runLock.tryLock()) {
			try {
				activeThread.set(Thread.currentThread());
				holocore = new HolocoreSocket(data.getAddress().getAddress(), data.getAddress().getPort());
				running.set(true);
				if (attemptConnection()) {
					while (holocore.isConnected()) {
						if (!connectionLoop())
							break;
					}
				}
			} catch (Throwable t) {
				Log.w(t);
				Log.i("Disconnected from server. Sleeping 3 seconds");
				holocore.disconnect(ServerConnectionChangedReason.UNKNOWN);
				queueConnectionLoop(3000);
			} finally {
				cleanupConnection();
				activeThread.set(null);
				runLock.unlock();
			}
		}
	}
	
	private boolean attemptConnection() {
		Log.t("Attempting to connect to server at %s", holocore.getRemoteAddress());
		if (!holocore.connect(5000)) {
			Log.t("Failed to connect to server. Sleeping 3 seconds");
			queueConnectionLoop(3000);
			return false;
		}
		intentChain.broadcastAfter(getIntentManager(), new ServerConnectedIntent());
		Log.i("Successfully connected to server at %s", holocore.getRemoteAddress());
		return true;
	}
	
	private boolean connectionLoop() {
		if (!running.get()) {
			holocore.disconnect(ServerConnectionChangedReason.CLIENT_DISCONNECT);
			return false;
		}
		
		RawPacket inbound = holocore.receive();
		if (inbound == null) {
			holocore.disconnect(ServerConnectionChangedReason.SOCKET_CLOSED);
			return false;
		}
		intentChain.broadcastAfter(getIntentManager(), new DataPacketOutboundIntent(interceptor.interceptServer(inbound.getData())));
		return true;
	}
	
	private void cleanupConnection() {
		intentChain.broadcastAfter(getIntentManager(), new ServerDisconnectedIntent());
		Log.i("Disconnected from server");
		holocore.terminate();
		holocore = null;
		running.set(false);
	}
	
}
