package com.projectswg;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import resources.network.NetBufferStream;
import network.PacketType;
import network.packets.swg.SWGPacket;
import network.packets.swg.holo.HoloConnectionStarted;
import network.packets.swg.holo.HoloConnectionStopped;
import network.packets.swg.holo.HoloSetProtocolVersion;
import network.packets.swg.zone.object_controller.ObjectController;

import com.projectswg.control.Intent;
import com.projectswg.control.Manager;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ClientToServerPacketIntent;
import com.projectswg.intents.ServerConnectionChangedIntent;
import com.projectswg.intents.ServerToClientPacketIntent;
import com.projectswg.networking.encryption.Compression;
import com.projectswg.resources.ServerConnectionStatus;
import com.projectswg.utilities.IntentChain;
import com.projectswg.utilities.Log;
import com.projectswg.utilities.ThreadUtilities;

public class ServerConnection extends Manager {
	
	private static final String PROTOCOL = "2016-04-13";
	
	private final Object bufferMutex;
	private final Object socketMutex;
	private final Queue<byte []> outQueue;
	private final IntentChain recvIntentChain;
	private ExecutorService connectionExecutor;
	private ExecutorService processExecutor;
	private AtomicBoolean running;
	private NetBufferStream bufferStream;
	private SocketChannel socket;
	private boolean connected;
	private ServerConnectionStatus status;
	private InetAddress addr;
	private int port;
	
	public ServerConnection(InetAddress addr, int port) {
		this.bufferMutex = new Object();
		this.socketMutex = new Object();
		this.outQueue = new LinkedList<>();
		this.recvIntentChain = new IntentChain();
		this.bufferStream = new NetBufferStream();
		this.running = new AtomicBoolean(false);
		this.addr = addr;
		this.port = port;
		status = ServerConnectionStatus.DISCONNECTED;
		socket = null;
		connected = false;
		stop();
	}
	
	@Override
	public boolean initialize() {
		registerForIntent(ClientConnectionChangedIntent.TYPE);
		registerForIntent(ClientToServerPacketIntent.TYPE);
		reset();
		return super.initialize();
	}
	
	@Override
	public void onIntentReceived(Intent i) {
		if (i instanceof ClientConnectionChangedIntent)
			processClientConnectionChanged((ClientConnectionChangedIntent) i);
		else if (i instanceof ClientToServerPacketIntent)
			send(((ClientToServerPacketIntent) i).getData());
	}
	
	public void setRemoteAddress(InetAddress addr, int port) {
		this.addr = addr;
		this.port = port;
	}
	
	public boolean send(byte [] raw) {
		if (status != ServerConnectionStatus.CONNECTED) {
			outQueue.add(raw);
			return false;
		}
		return sendForce(raw);
	}
	
	private boolean sendForce(byte [] raw) {
		int decompressedLength = raw.length;
		boolean compressed = raw.length >= 16;
		if (compressed) {
			byte [] compressedData = Compression.compress(raw);
			if (compressedData.length >= raw.length)
				compressed = false;
			else
				raw = compressedData;
		}
		ByteBuffer data = ByteBuffer.allocate(raw.length + 5).order(ByteOrder.LITTLE_ENDIAN);
		data.put(createBitmask(compressed, true));
		data.putShort((short) raw.length);
		data.putShort((short) decompressedLength);
		data.put(raw);
		data.flip();
		try {
			if (socket != null) {
				socket.write(data);
				return true;
			}
		} catch (IOException e) {
			Log.err(this, e);
			disconnect(ServerConnectionStatus.OTHER_SIDE_TERMINATED);
		}
		return false;
	}
	
	private void processClientConnectionChanged(ClientConnectionChangedIntent ccci) {
		switch (ccci.getStatus()) {
			case LOGIN_CONNECTED:
			case ZONE_CONNECTED:
				startServer();
				break;
			case DISCONNECTED:
				stopServer();
				break;
			default:
				break;
		}
	}
	
	private void startServer() {
		if (running.getAndSet(true))
			return;
		connectionExecutor = Executors.newSingleThreadExecutor(ThreadUtilities.newThreadFactory("server-conn-connection"));
		processExecutor = Executors.newSingleThreadExecutor(ThreadUtilities.newThreadFactory("server-conn-processor"));
		connectionExecutor.execute(() -> run());
	}
	
	private void stopServer() {
		if (!running.getAndSet(false))
			return;
		disconnect(ServerConnectionStatus.DISCONNECTED);
		safeShutdown(connectionExecutor);
		safeShutdown(processExecutor);
		connectionExecutor = null;
		processExecutor = null;
	}
	
	private boolean processPacket() {
		byte bitmask;
		short messageLength, decompressedLength;
		byte [] message;
		synchronized (bufferStream) {
			bufferStream.mark();
			bitmask = bufferStream.getByte();
			messageLength = bufferStream.getShort();
			decompressedLength = bufferStream.getShort();
			if (bufferStream.remaining() < messageLength) {
				bufferStream.rewind();
				return false;
			}
			message = bufferStream.getArray(messageLength);
		}
		if ((bitmask & 1) != 0) // Compressed
			message = Compression.decompress(message, decompressedLength);
		if (message.length < 6)
			return true;
		processPacketToSwg(message);
		return true;
	}
	
	private void processPacketToSwg(byte [] packet) {
		ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
		int crc = data.getInt(2);
		SWGPacket swg;
		if (crc == 0x80CE5E46)
			swg = ObjectController.decodeController(data);
		else {
			swg = PacketType.getForCrc(crc);
			if (swg != null)
				swg.decode(data);
		}
		if (swg != null)
			processPacket(swg, packet);
		else
			Log.err(this, "Incoming packet is null! Data: " + Arrays.toString(packet));
	}
	
	private void processPacket(SWGPacket packet, byte [] raw) {
		recvIntentChain.broadcastAfter(new ServerToClientPacketIntent(packet, raw), getIntentManager());
		if (packet instanceof HoloConnectionStarted) {
			updateStatus(ServerConnectionStatus.CONNECTED);
			while (!outQueue.isEmpty())
				send(outQueue.poll());
			Log.out(this, "Server connected");
		} else if (packet instanceof HoloConnectionStopped) {
			switch (((HoloConnectionStopped) packet).getReason()) {
				case INVALID_PROTOCOL:
					disconnect(ServerConnectionStatus.DISCONNECT_INVALID_PROTOCOL);
					break;
				default:
					disconnect(ServerConnectionStatus.DISCONNECTED);
					break;
			}
			stopServer();
		}
	}
	
	private void run() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(4*1024);
		Log.out(this, "Started ServerConnection");
		int loopAttempts = 0;
		try {
			while (running.get()) {
				if (!connected) {
					loopDisconnected();
					loopAttempts++;
					if (loopAttempts >= 3)
						stopServer();
				} else {
					read(buffer);
					loopAttempts = 0;
				}
			}
		} catch (InterruptedException e) {
			
		} catch (Exception e) {
			Log.err(this, e);
		}
	}
	
	private boolean loopDisconnected() throws InterruptedException {
		boolean connecting = false;
		synchronized (socketMutex) {
			if (socket == null) {
				connecting = connect();
			} else {
				connected = socket.isConnected();
				connecting = true;
			}
		}
		if (connected) {
			Log.out(this, "Server connecting...");
			updateStatus(ServerConnectionStatus.CONNECTING);
			sendForce(new HoloSetProtocolVersion(PROTOCOL).encode().array());
		} else
			Thread.sleep(connecting ? 5 : 1000);
		return connected;
	}
	
	private void read(ByteBuffer data) {
		try {
			data.position(0);
			data.limit(data.capacity());
			int n = socket.read(data);
			if (n < 0) {
				disconnect(ServerConnectionStatus.OTHER_SIDE_TERMINATED);
			} else if (n > 0) {
				data.flip();
				addToBuffer(data);
			}
		} catch (IOException e) {
			if (e instanceof AsynchronousCloseException)
				disconnect(ServerConnectionStatus.DISCONNECTED);
			else if (e.getMessage() != null)
				disconnect(getReason(e.getMessage()));
			else {
				disconnect(ServerConnectionStatus.DISCONNECT_UNKNOWN_REASON);
				e.printStackTrace();
			}
		} catch (Exception e) {
			Log.err(this, "Failed to process buffer!");
			Log.err(this, e);
			disconnect(ServerConnectionStatus.DISCONNECT_INTERNAL_ERROR);
		}
	}
	
	private void addToBuffer(ByteBuffer data) {
		if (!running.get())
			return;
		bufferStream.write(data);
		if (running.get())
			processExecutor.execute(() -> process());
	}
	
	private void process() {
		while (bufferStream.remaining() >= 5) {
			if (!processPacket())
				break;
		}
		synchronized (bufferMutex) {
			bufferStream.compact();
		}
	}
	
	private void reset() {
		synchronized (bufferMutex) {
			bufferStream.reset();
		}
	}
	
	private boolean connect() {
		synchronized (socketMutex) {
			try {
				if (socket != null)
					disconnect(ServerConnectionStatus.DISCONNECTED);
				socket = SocketChannel.open(new InetSocketAddress(addr, port));
				reset();
				return true;
			} catch (IOException e) {
				if (e.getMessage() == null) {
					disconnect(ServerConnectionStatus.DISCONNECT_UNKNOWN_REASON);
					e.printStackTrace();
				} else
					disconnect(getReason(e.getMessage()));
				return false;
			}
		}
	}
	
	private boolean disconnect(ServerConnectionStatus status) {
		synchronized (socketMutex) {
			updateStatus(status);
			if (!connected)
				return true;
			if (socket == null)
				return true;
			Log.out(this, "Server disconnected");
			connected = false;
			try {
				socket.close();
				socket = null;
				reset();
				return true;
			} catch (IOException e) {
				Log.err(this, e);
				return false;
			}
		}
	}
	
	private void updateStatus(ServerConnectionStatus status) {
		ServerConnectionStatus old = this.status;
		if (old != status) {
			this.status = status;
			ServerConnectionChangedIntent i = new ServerConnectionChangedIntent(old, status);
			try {
				if (socket != null) {
					SocketAddress source = socket.getLocalAddress();
					SocketAddress destination = socket.getRemoteAddress();
					if (source instanceof InetSocketAddress)
						i.setSource((InetSocketAddress) source);
					if (destination instanceof InetSocketAddress)
						i.setDestination((InetSocketAddress) destination);
				}
			} catch (IOException e) {
				Log.err(this, e);
			}
			recvIntentChain.broadcastAfter(i, getIntentManager());
		}
	}
	
	private byte createBitmask(boolean compressed, boolean swg) {
		byte bitfield = 0;
		bitfield |= (compressed?1:0) << 0;
		bitfield |= (swg?1:0) << 1;
		return bitfield;
	}
	
	private ServerConnectionStatus getReason(String message) {
		if (message.toLowerCase(Locale.US).contains("broken pipe"))
			return ServerConnectionStatus.BROKEN_PIPE;
		if (message.toLowerCase(Locale.US).contains("connection reset"))
			return ServerConnectionStatus.CONNECTION_RESET;
		if (message.toLowerCase(Locale.US).contains("connection refused"))
			return ServerConnectionStatus.CONNECTION_REFUSED;
		if (message.toLowerCase(Locale.US).contains("address in use"))
			return ServerConnectionStatus.ADDR_IN_USE;
		if (message.toLowerCase(Locale.US).contains("socket closed"))
			return ServerConnectionStatus.DISCONNECTED;
		if (message.toLowerCase(Locale.US).contains("no route to host"))
			return ServerConnectionStatus.NO_ROUTE_TO_HOST;
		Log.err(this, "Unknown reason: " + message);
		return ServerConnectionStatus.DISCONNECT_UNKNOWN_REASON;
	}
	
	private boolean safeShutdown(ExecutorService ex) {
		if (ex == null)
			return true;
		ex.shutdownNow();
		return attemptAwaitTermination(ex);
	}
	
	private boolean attemptAwaitTermination(ExecutorService ex) {
		try {
			return ex.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			return false;
		}
	}
	
}
