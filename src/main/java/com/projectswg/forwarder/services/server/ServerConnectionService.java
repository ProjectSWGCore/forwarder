package com.projectswg.forwarder.services.server;

import com.projectswg.connection.HolocoreSocket;
import com.projectswg.connection.ServerConnectionChangedReason;
import com.projectswg.connection.packets.RawPacket;
import com.projectswg.forwarder.Forwarder.ForwarderData;
import com.projectswg.forwarder.intents.client.DataPacketInboundIntent;
import com.projectswg.forwarder.intents.client.DataPacketOutboundIntent;
import com.projectswg.forwarder.intents.client.ClientConnectedIntent;
import com.projectswg.forwarder.intents.client.ClientDisconnectedIntent;
import com.projectswg.forwarder.intents.control.StartForwarderIntent;
import com.projectswg.forwarder.intents.control.StopForwarderIntent;
import com.projectswg.forwarder.intents.server.ServerConnectedIntent;
import com.projectswg.forwarder.intents.server.ServerDisconnectedIntent;
import com.projectswg.forwarder.resources.networking.NetInterceptor;
import me.joshlarson.jlcommon.concurrency.BasicThread;
import me.joshlarson.jlcommon.concurrency.Delay;
import me.joshlarson.jlcommon.control.IntentChain;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.Service;
import me.joshlarson.jlcommon.log.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public class ServerConnectionService extends Service {
	
	private final IntentChain intentChain;
	private final AtomicBoolean running;
	private final BasicThread thread;
	
	private HolocoreSocket holocore;
	private NetInterceptor interceptor;
	private ForwarderData data;
	
	public ServerConnectionService() {
		this.intentChain = new IntentChain();
		this.running = new AtomicBoolean(false);
		this.thread = new BasicThread("server-connection", this::runningLoop);
		this.holocore = null;
		this.interceptor = null;
		this.data = null;
	}
	
	@Override
	public boolean stop() {
		return stopRunningLoop();
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
		stopRunningLoop();
		thread.start();
	}
	
	@IntentHandler
	private void handleClientDisconnectedIntent(ClientDisconnectedIntent cdi) {
		stopRunningLoop();
	}
	
	@IntentHandler
	private void handleDataPacketInboundIntent(DataPacketInboundIntent dpii) {
		if (running.get())
			holocore.send(interceptor.interceptClient(dpii.getData()));
	}
	
	private boolean stopRunningLoop() {
		if (running.getAndSet(false)) {
			thread.stop(true);
			return thread.awaitTermination(500);
		}
		return true;
	}
	
	private void runningLoop() {
		holocore = new HolocoreSocket(data.getAddress().getAddress(), data.getAddress().getPort());
		running.set(true);
		while (running.get()) {
			try {
				connectedLoop();
			} catch (Throwable t) {
				Log.w(t);
				Log.i("Disconnected from server. Sleeping 3 seconds");
				holocore.disconnect(ServerConnectionChangedReason.UNKNOWN);
				Delay.sleepMilli(3000);
			}
		}
		Log.t("Destroying holocore connection");
		holocore.terminate();
		holocore = null;
	}
	
	private void connectedLoop() {
		Log.t("Attempting to connect to server at %s", holocore.getRemoteAddress());
		if (!holocore.connect(5000)) {
			Log.t("Failed to connect to server. Sleeping 3 seconds");
			Delay.sleepMilli(3000);
			return;
		}
		intentChain.broadcastAfter(getIntentManager(), new ServerConnectedIntent());
		Log.i("Successfully connected to server at %s", holocore.getRemoteAddress());
		while (holocore.isConnected()) {
			if (!running.get()) {
				holocore.disconnect(ServerConnectionChangedReason.CLIENT_DISCONNECT);
				break;
			}
			
			RawPacket inbound = holocore.receive();
			if (inbound == null) {
				holocore.disconnect(ServerConnectionChangedReason.SOCKET_CLOSED);
				break;
			}
			intentChain.broadcastAfter(getIntentManager(), new DataPacketOutboundIntent(interceptor.interceptServer(inbound.getData())));
		}
		intentChain.broadcastAfter(getIntentManager(), new ServerDisconnectedIntent());
		Log.i("Disconnected from server");
	}
	
}
