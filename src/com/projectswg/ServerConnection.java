package com.projectswg;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectswg.networking.encryption.Compression;

public class ServerConnection {
	
	private static final int DEFAULT_BUFFER = 4096;
	
	private final Object bufferMutex;
	private final Object socketMutex;
	private final Queue<byte []> outQueue;
	private ExecutorService processorExecutor;
	private ExecutorService callbackExecutor;
	private ExecutorService connectionExecutor;
	private AtomicBoolean running;
	private ByteBuffer buffer;
	private long lastBufferSizeModification;
	private SocketChannel socket;
	private boolean connected;
	private ServerCallback callback;
	private ConnectionStatus status;
	private InetAddress addr;
	private int port;
	
	public ServerConnection(InetAddress addr, int port) {
		this.bufferMutex = new Object();
		this.socketMutex = new Object();
		this.outQueue = new LinkedList<>();
		this.buffer = ByteBuffer.allocate(DEFAULT_BUFFER).order(ByteOrder.LITTLE_ENDIAN);
		this.running = new AtomicBoolean(false);
		lastBufferSizeModification = System.nanoTime();
		this.addr = addr;
		this.port = port;
		status = ConnectionStatus.DISCONNECTED;
		socket = null;
		callback = null;
		connected = false;
		stop();
	}
	
	public void start() {
		if (running.get())
			return;
		processorExecutor = Executors.newSingleThreadExecutor();
		connectionExecutor = Executors.newSingleThreadExecutor();
		callbackExecutor = Executors.newSingleThreadExecutor();
		running.set(true);
		connectionExecutor.execute(() -> run());
	}
	
	public void stop() {
		if (!running.get())
			return;
		running.set(false);
		disconnect(ConnectionStatus.DISCONNECTED);
		safeShutdown(connectionExecutor);
		safeShutdown(processorExecutor);
		safeShutdown(callbackExecutor);
		processorExecutor = null;
		connectionExecutor = null;
		callbackExecutor = null;
	}
	
	public void setRemoteAddress(InetAddress addr, int port) {
		this.addr = addr;
		this.port = port;
	}
	
	public void setCallback(ServerCallback callback) {
		this.callback = callback;
	}
	
	public boolean send(byte [] raw) {
		if (!connected) {
			outQueue.add(raw);
			return false;
		}
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
			socket.write(data);
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			disconnect(ConnectionStatus.OTHER_SIDE_TERMINATED);
			return false;
		}
	}
	
	private boolean processPacket() {
		byte bitmask = buffer.get();
		short messageLength = buffer.getShort();
		short decompressedLength = buffer.getShort();
		if (buffer.remaining() < messageLength) {
			buffer.position(buffer.position() - 5);
			return false;
		}
		byte [] message = new byte[messageLength];
		buffer.get(message);
		final byte [] packet;
		if ((bitmask & 1) != 0) // Compressed
			packet = Compression.decompress(message, decompressedLength);
		else
			packet = message;
		if (callback != null && callbackExecutor != null)
			callbackExecutor.execute(() -> callback.onData(packet));
		return true;
	}
	
	private void run() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(4*1024);
		try {
			while (running.get()) {
				if (!connected)
					loopDisconnected();
				else
					read(buffer);
			}
		} catch (InterruptedException e) {
			
		} catch (Exception e) {
			e.printStackTrace();
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
			while (!outQueue.isEmpty())
				send(outQueue.poll());
			updateStatus(ConnectionStatus.CONNECTED);
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
				disconnect(ConnectionStatus.OTHER_SIDE_TERMINATED);
			} else if (n > 0) {
				data.flip();
				addToBuffer(data);
			}
		} catch (IOException e) {
			if (e.getMessage() == null)
				disconnect(ConnectionStatus.DISCONNECT_UNKNOWN_REASON);
			else
				disconnect(getReason(e.getMessage()));
		} catch (Exception e) {
			System.err.println("Failed to process buffer!");
			e.printStackTrace();
			System.exit(0);
		}
	}
	
	private void addToBuffer(ByteBuffer data) {
		if (!running.get())
			return;
		synchronized (bufferMutex) {
			if (data.remaining() > buffer.remaining()) { // Increase size
				int nCapacity = buffer.capacity() * 2;
				while (nCapacity < buffer.position()+data.remaining())
					nCapacity *= 2;
				ByteBuffer bb = ByteBuffer.allocate(nCapacity).order(ByteOrder.LITTLE_ENDIAN);
				buffer.flip();
				bb.put(buffer);
				bb.put(data);
				buffer = bb;
				lastBufferSizeModification = System.nanoTime();
			} else {
				buffer.put(data);
				if (buffer.position() < buffer.capacity()/4 && data.limit() != data.capacity() && (System.nanoTime()-lastBufferSizeModification) >= 1E9)
					shrinkBuffer();
			}
		}
		if (running.get())
			processorExecutor.execute(() -> process());
	}
	
	private void shrinkBuffer() {
		synchronized (bufferMutex) {
			int nCapacity = DEFAULT_BUFFER;
			while (nCapacity < buffer.position())
				nCapacity *= 2;
			if (nCapacity >= buffer.capacity())
				return;
			ByteBuffer bb = ByteBuffer.allocate(nCapacity).order(ByteOrder.LITTLE_ENDIAN);
			buffer.flip();
			bb.put(buffer);
			buffer = bb;
			lastBufferSizeModification = System.nanoTime();
		}
	}
	
	private void process() {
		synchronized (bufferMutex) {
			buffer.flip();
			while (buffer.remaining() >= 5) {
				if (!processPacket())
					break;
			}
			buffer.compact();
		}
	}
	
	private void reset() {
		synchronized (bufferMutex) {
			buffer = ByteBuffer.allocate(DEFAULT_BUFFER).order(ByteOrder.LITTLE_ENDIAN);
			lastBufferSizeModification = System.nanoTime();
		}
	}
	
	private boolean connect() {
		synchronized (socketMutex) {
			try {
				if (socket != null)
					disconnect(ConnectionStatus.DISCONNECTED);
				socket = SocketChannel.open(new InetSocketAddress(addr, port));
				reset();
				return true;
			} catch (IOException e) {
				if (e.getMessage() == null)
					disconnect(ConnectionStatus.DISCONNECT_UNKNOWN_REASON);
				else
					disconnect(getReason(e.getMessage()));
				return false;
			}
		}
	}
	
	private boolean disconnect(ConnectionStatus status) {
		synchronized (socketMutex) {
			if (!connected)
				return true;
			if (socket == null)
				return true;
			connected = false;
			updateStatus(status);
			try {
				socket.close();
				socket = null;
				reset();
				return true;
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			}
		}
	}
	
	private void updateStatus(ConnectionStatus status) {
		ConnectionStatus old = this.status;
		this.status = status;
		if (callback != null && callbackExecutor != null && old != status)
			callbackExecutor.execute(() -> callback.onStatusChanged(old, status) );
	}
	
	private byte createBitmask(boolean compressed, boolean swg) {
		byte bitfield = 0;
		bitfield |= (compressed?1:0) << 0;
		bitfield |= (swg?1:0) << 1;
		return bitfield;
	}
	
	private ConnectionStatus getReason(String message) {
		if (message.toLowerCase(Locale.US).contains("broken pipe"))
			return ConnectionStatus.BROKEN_PIPE;
		if (message.toLowerCase(Locale.US).contains("connection reset"))
			return ConnectionStatus.CONNECTION_RESET;
		if (message.toLowerCase(Locale.US).contains("connection refused"))
			return ConnectionStatus.CONNECTION_REFUSED;
		if (message.toLowerCase(Locale.US).contains("address in use"))
			return ConnectionStatus.ADDR_IN_USE;
		if (message.toLowerCase(Locale.US).contains("socket closed"))
			return ConnectionStatus.DISCONNECTED;
		if (message.toLowerCase(Locale.US).contains("no route to host"))
			return ConnectionStatus.NO_ROUTE_TO_HOST;
		System.err.println("Unknown reason: " + message);
		return ConnectionStatus.DISCONNECT_UNKNOWN_REASON;
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
	
	public interface ServerCallback {
		void onStatusChanged(ConnectionStatus oldStatus, ConnectionStatus status);
		void onData(byte [] data);
	}
	
	public enum ConnectionStatus {
		CONNECTED,
		BROKEN_PIPE,
		CONNECTION_RESET,
		CONNECTION_REFUSED,
		ADDR_IN_USE,
		OTHER_SIDE_TERMINATED,
		NO_ROUTE_TO_HOST,
		DISCONNECT_UNKNOWN_REASON,
		DISCONNECTED
	}
	
}
