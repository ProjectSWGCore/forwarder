package com.projectswg.networking.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import network.packets.swg.zone.HeartBeat;

import com.projectswg.concurrency.PswgBasicScheduledThread;
import com.projectswg.concurrency.PswgBasicThread;
import com.projectswg.connection.HolocoreSocket;
import com.projectswg.connection.ServerConnectionChangedReason;
import com.projectswg.connection.packets.RawPacket;
import com.projectswg.control.Assert;
import com.projectswg.control.IntentManager;
import com.projectswg.control.Service;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ClientToServerPacketIntent;
import com.projectswg.intents.ServerConnectionChangedIntent;
import com.projectswg.intents.ServerToClientPacketIntent;
import com.projectswg.utilities.IntentChain;
import com.projectswg.utilities.Log;
import com.projectswg.utilities.ThreadUtilities;

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
		private final AtomicBoolean executing;
		private final AtomicLong lastHeartbeat;
		private final PswgBasicThread thread;
		private final PswgBasicScheduledThread heartbeatThread;
		private final Queue<byte []> outQueue;
		private IntentChain recvIntentChain;
		
		public ConnectionThread(HolocoreSocket connection) {
			this.connection = connection;
			this.executing = new AtomicBoolean(false);
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
			thread.start();
		}
		
		public void stop() {
			thread.stop();
			thread.awaitTermination(5000);
		}
		
		public void addToOutQueue(byte [] raw) {
			synchronized (outQueue) {
				outQueue.add(raw);
			}
		}
		
		private void heartbeat() {
			if (connection.isConnected()) {
				if (System.nanoTime() - lastHeartbeat.get() >= HOLOCORE_TIMEOUT) {
					stop();
					return;
				}
				connection.send(new HeartBeat().encode().array());
			}
		}
		
		private void run() {
			Log.out(this, "Started ServerConnection");
			try {
				executing.set(true);
				heartbeatThread.startWithFixedDelay(0, 10*1000);
				while (thread.isRunning()) {
					if (connection.isDisconnected() && !tryConnect()) {
						ThreadUtilities.sleep(1000);
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
				Log.err(this, t);
			} finally {
				heartbeatThread.stop();
				connection.disconnect(ServerConnectionChangedReason.NONE);
				executing.set(false);
			}
			Log.out(this, "Stopped ServerConnection");
		}
		
		private boolean tryConnect() {
			if (!connection.connect())
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
