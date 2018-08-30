package com.projectswg.forwarder.services.server;

import com.projectswg.connection.HolocoreSocket;
import com.projectswg.connection.RawPacket;
import com.projectswg.forwarder.Forwarder.ForwarderData;
import com.projectswg.forwarder.intents.client.ClientDisconnectedIntent;
import com.projectswg.forwarder.intents.client.DataPacketInboundIntent;
import com.projectswg.forwarder.intents.client.DataPacketOutboundIntent;
import com.projectswg.forwarder.intents.control.StartForwarderIntent;
import com.projectswg.forwarder.intents.control.StopForwarderIntent;
import com.projectswg.forwarder.intents.server.RequestServerConnectionIntent;
import com.projectswg.forwarder.intents.server.ServerConnectedIntent;
import com.projectswg.forwarder.intents.server.ServerDisconnectedIntent;
import com.projectswg.forwarder.resources.networking.NetInterceptor;
import me.joshlarson.jlcommon.concurrency.BasicThread;
import me.joshlarson.jlcommon.control.IntentChain;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.Service;
import me.joshlarson.jlcommon.log.Log;

public class ServerConnectionService extends Service {
	
	private static final int CONNECT_TIMEOUT = 5000;
	
	private final IntentChain intentChain;
	private final BasicThread thread;
	
	private HolocoreSocket holocore;
	private NetInterceptor interceptor;
	private ForwarderData data;
	
	public ServerConnectionService() {
		this.intentChain = new IntentChain();
		this.thread = new BasicThread("server-connection", this::primaryConnectionLoop);
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
	private void handleRequestServerConnectionIntent(RequestServerConnectionIntent rsci) {
		if (thread.isExecuting())
			stopRunningLoop();
		thread.start();
	}
	
	@IntentHandler
	private void handleClientDisconnectedIntent(ClientDisconnectedIntent cdi) {
		stopRunningLoop();
	}
	
	@IntentHandler
	private void handleDataPacketInboundIntent(DataPacketInboundIntent dpii) {
		HolocoreSocket holocore = this.holocore;
		if (holocore != null)
			holocore.send(interceptor.interceptClient(dpii.getData()));
	}
	
	private boolean stopRunningLoop() {
		thread.stop(true);
		return thread.awaitTermination(1000);
	}
	
	private void primaryConnectionLoop() {
		try (HolocoreSocket holocore = new HolocoreSocket(data.getAddress().getAddress(), data.getAddress().getPort())) {
			this.holocore = holocore;
			Log.t("Attempting to connect to server at %s", holocore.getRemoteAddress());
			if (!holocore.connect(CONNECT_TIMEOUT)) { 
				Log.w("Failed to connect to server!");
				return;
			}
			Log.i("Successfully connected to server at %s", holocore.getRemoteAddress());
			
			intentChain.broadcastAfter(getIntentManager(), new ServerConnectedIntent());
			while (holocore.isConnected()) {
				RawPacket inbound = holocore.receive();
				if (inbound == null) {
					Log.w("Server closed connection!");
					return;
				}
				intentChain.broadcastAfter(getIntentManager(), new DataPacketOutboundIntent(interceptor.interceptServer(inbound.getData())));
			}
		} catch (Throwable t) {
			Log.w("Caught unknown exception in server connection! %s: %s", t.getClass().getName(), t.getMessage());
			Log.w(t);
		} finally {
			Log.i("Disconnected from server.");
			intentChain.broadcastAfter(getIntentManager(), new ServerDisconnectedIntent());
			this.holocore = null;
		}
	}
	
}
