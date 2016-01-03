package com.projectswg;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.Queue;

import com.projectswg.networking.encryption.Compression;

public class ServerConnection {
	
	private static final InetAddress REMOTE_ADDR;
	private static final int REMOTE_PORT = 44463;
	
	static {
		InetAddress addr;
		try {
			addr = InetAddress.getByName("::1");
		} catch (UnknownHostException e) {
			addr = InetAddress.getLoopbackAddress();
			e.printStackTrace();
		}
		REMOTE_ADDR = addr;
	}
	
	private final Object bufferMutex;
	private Socket socket;
	private boolean connected;
	private byte [] buffer;
	private ServerCallback callback;
	private Queue<byte []> outQueue;
	
	private Thread thread;
	private boolean running;
	
	public ServerConnection() {
		bufferMutex = new Object();
		outQueue = new LinkedList<>();
		socket = null;
		thread = null;
		callback = null;
		running = false;
		connected = false;
		buffer = new byte[0];
	}
	
	public void start() {
		stop();
		running = true;
		thread = new Thread(() -> run());
		thread.start();
	}
	
	public void stop() {
		running = false;
		disconnect();
		if (thread != null)
			thread.interrupt();
		thread = null;
	}
	
	public void setCallback(ServerCallback callback) {
		this.callback = callback;
	}
	
	public boolean forward(byte [] raw) {
		if (socket == null) {
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
		try {
			socket.getOutputStream().write(data.array());
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	private boolean processPacket(ByteBuffer data) {
		byte bitmask = data.get();
		short messageLength = data.getShort();
		short decompressedLength = data.getShort();
		if (data.remaining() < messageLength) {
			data.position(data.position() - 5);
			return false;
		}
		byte [] message = new byte[messageLength];
		data.get(message);
		if ((bitmask & 1) != 0) // Compressed
			message = Compression.decompress(message, decompressedLength);
		if (callback != null)
			callback.onData(message);
		return true;
	}
	
	private void run() {
		if (!connected)
			connect();
		byte [] buffer = new byte[2*1024];
		while (running) {
			if (!connected) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					break;
				}
				connect();
			} else {
				read(buffer);
			}
		}
	}
	
	private void read(byte [] buffer) {
		try {
			InputStream input = socket.getInputStream();
			int n = input.read(buffer);
			if (n == -1)
				disconnect();
			else
				process(buffer, n);
		} catch (IOException e) {
			if (connected) {
				e.printStackTrace();
				disconnect();
			}
		}
	}
	
	private void process(byte [] buffer, int length) {
		if (length <= 0)
			return;
		ByteBuffer data = ByteBuffer.allocate(length+this.buffer.length).order(ByteOrder.LITTLE_ENDIAN);
		data.put(this.buffer);
		data.put(buffer, 0, length);
		data.position(0);
		while (data.remaining() >= 5) {
			if (!processPacket(data))
				break;
		}
		synchronized (bufferMutex) {
			byte [] tmp = new byte[data.remaining()];
			data.get(tmp);
			this.buffer = tmp;
		}
	}
	
	private boolean connect() {
		try {
			if (socket != null)
				disconnect();
			socket = new Socket(REMOTE_ADDR, REMOTE_PORT);
			while (!outQueue.isEmpty())
				forward(outQueue.poll());
			if (!connected && callback != null)
				callback.onConnected();
			connected = true;
			return true;
		} catch (IOException e) {
			connected = false;
			return false;
		}
	}
	
	private boolean disconnect() {
		if (connected && callback != null)
			callback.onDisconnected();
		connected = false;
		if (socket == null)
			return true;
		try {
			socket.close();
			socket = null;
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	private byte createBitmask(boolean compressed, boolean swg) {
		byte bitfield = 0;
		bitfield |= (compressed?1:0) << 0;
		bitfield |= (swg?1:0) << 1;
		return bitfield;
	}
	
	public interface ServerCallback {
		void onConnected();
		void onDisconnected();
		void onData(byte [] data);
	}
	
}
