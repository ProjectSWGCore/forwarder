package com.projectswg.networking.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.projectswg.common.concurrency.Delay;
import com.projectswg.common.concurrency.PswgBasicScheduledThread;
import com.projectswg.common.concurrency.PswgBasicThread;
import com.projectswg.common.control.IntentChain;
import com.projectswg.common.control.IntentManager;
import com.projectswg.common.control.Service;
import com.projectswg.common.debug.Assert;
import com.projectswg.common.debug.Log;
import com.projectswg.connection.HolocoreSocket;
import com.projectswg.connection.ServerConnectionChangedReason;
import com.projectswg.connection.packets.RawPacket;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ClientToServerPacketIntent;
import com.projectswg.intents.ServerConnectionChangedIntent;
import com.projectswg.intents.ServerToClientPacketIntent;

import com.projectswg.common.network.packets.swg.zone.HeartBeat;

public class ServerConnectionService extends Service {
	
	private static final long HOLOCORE_TIMEOUT = TimeUnit.SECONDS.toNanos(21);
	
	private final HolocoreSocket socket;
	private final ConnectionThread connectionThread;
	private IntentChain socketIntentChain;
	
	public ServerConnectionService(InetAddress addr, int port) {
		this.socket = new HolocoreSocket(addr, port);
		this.connectionThread = new ConnectionThread(socket);
	}
	
	@Override
	public boolean initialize() {
		socketIntentChain = new IntentChain(getIntentManager());
		connectionThread.initialize(getIntentManager());
		this.socket.setStatusChangedCallback((o, n, reason) -> socketIntentChain.broadcastAfter(new ServerConnectionChangedIntent(o, n, reason)));
		registerForIntent(ClientConnectionChangedIntent.class, ccci -> processClientConnectionChanged(ccci));
		registerForIntent(ClientToServerPacketIntent.class, ctspi -> send(ctspi.getData()));
		return super.initialize();
	}
	
	@Override
	public boolean terminate() {
		socket.terminate();
		stopServer();
		return super.terminate();
	}
	
	public void setRemoteAddress(InetAddress addr, int port) {
		socket.setRemoteAddress(addr, port);
	}
	
	public InetSocketAddress getRemoteAddress() {
		return socket.getRemoteAddress();
	}
	
	public boolean send(byte [] raw) {
		if (!socket.isConnected()) {
			connectionThread.addToOutQueue(raw);
			return false;
		}
		return socket.send(raw);
	}
	
	private void processClientConnectionChanged(ClientConnectionChangedIntent ccci) {
		Log.d("processClientConnectionChanged(%s)", ccci.getStatus());
		switch (ccci.getStatus()) {
			case LOGIN_CONNECTED:
				startServer();
				break;
			case DISCONNECTED:
				stopServer();
				connectionThread.reset();
				break;
			default:
				break;
		}
	}
	
	private void startServer() {
		connectionThread.start();
	}
	
	private void stopServer() {
		connectionThread.stop();
	}
	
	private static class ConnectionThread {
		
		private final HolocoreSocket connection;
		private final AtomicLong lastHeartbeat;
		private final PswgBasicThread thread;
		private final PswgBasicScheduledThread heartbeatThread;
		private final Queue<byte []> outQueue;
		private IntentChain recvIntentChain;
		
		public ConnectionThread(HolocoreSocket connection) {
			this.connection = connection;
			this.lastHeartbeat = new AtomicLong(0);
			this.thread = new PswgBasicThread("server-connection", () -> run());
			this.heartbeatThread = new PswgBasicScheduledThread("server-heartbeat", () -> heartbeat());
			this.outQueue = new LinkedList<>();
			this.recvIntentChain = null;
		}
		
		public void initialize(IntentManager intentManager) {
			this.recvIntentChain = new IntentChain(intentManager);
		}
		
		public void reset() {
			outQueue.clear();
		}
		
		public void start() {
			if (thread.isRunning())
				return;
			thread.start();
			heartbeatThread.startWithFixedDelay(0, 10*1000);
		}
		
		public void stop() {
			if (!thread.isRunning())
				return;
			thread.stop(true);
			heartbeatThread.stop();
			thread.awaitTermination(5000);
			heartbeatThread.awaitTermination(1000);
		}
		
		public void addToOutQueue(byte [] raw) {
			synchronized (outQueue) {
				outQueue.add(raw);
			}
		}
		
		private void heartbeat() {
			if (connection.isConnected()) {
				long lastHeartbeat = this.lastHeartbeat.get();
				if (lastHeartbeat != 0 && System.nanoTime() - lastHeartbeat >= HOLOCORE_TIMEOUT) {
					stop();
					return;
				}
				connection.send(new HeartBeat().encode().array());
			}
		}
		
		private void run() {
			Log.i("Started ServerConnection");
			try {
				while (thread.isRunning()) {
					if (connection.isDisconnected() && !tryConnect()) {
						Delay.sleepMilli(1000);
						continue;
					}
					Assert.test(connection.isConnected());
					RawPacket packet = null;
					while ((packet = connection.receive()) != null) {
						if (packet.getCrc() == HeartBeat.CRC)
							lastHeartbeat.set(System.nanoTime());
						recvIntentChain.broadcastAfter(new ServerToClientPacketIntent(packet.getCrc(), packet.getData()));
					}
				}
			} catch (Throwable t) {
				Log.e(t);
			} finally {
				connection.disconnect(ServerConnectionChangedReason.NONE);
			}
			Log.i("Stopped ServerConnection");
		}
		
		private boolean tryConnect() {
			if (!connection.connect(10000))
				return false;
			synchronized (outQueue) {
				while (!outQueue.isEmpty()) {
					if (!connection.send(outQueue.poll()))
						return false;
				}
			}
			return true;
		}
		
	}
	
}
