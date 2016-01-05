package com.projectswg;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.Queue;

import com.projectswg.networking.encryption.Compression;

public class ServerConnection {
	
	private final Object bufferMutex;
	private final Queue<byte []> outQueue;
	private Socket socket;
	private boolean connected;
	private byte [] buffer;
	private ServerCallback callback;
	private InetAddress addr;
	private int port;
	
	private Thread thread;
	private boolean running;
	
	public ServerConnection(InetAddress addr, int port) {
		this.bufferMutex = new Object();
		this.addr = addr;
		this.port = port;
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
	
	public void setRemoteAddress(InetAddress addr, int port) {
		this.addr = addr;
		this.port = port;
	}
	
	public void setCallback(ServerCallback callback) {
		this.callback = callback;
	}
	
	public boolean send(byte [] raw) {
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
		InputStream input = null;
		if (!connected) {
			if (connect()) {
				try {
					input = socket.getInputStream();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		byte [] buffer = new byte[2*1024];
		while (running) {
			if (!connected) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					break;
				}
				if (connect()) {
					try {
						input = socket.getInputStream();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			} else {
				read(input, buffer);
			}
		}
	}
	
	private void read(InputStream input, byte [] buffer) {
		try {
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
		} catch (Exception e) {
			System.err.println("Failed to process buffer!");
			e.printStackTrace();
			System.exit(0);
		}
	}
	
	private void process(byte [] buffer, int length) {
		if (length <= 0)
			return;
		ByteBuffer data;
		synchronized (bufferMutex) {
			data = ByteBuffer.allocate(length+this.buffer.length).order(ByteOrder.LITTLE_ENDIAN);
			data.put(this.buffer);
		}
		data.put(buffer, 0, length);
		data.flip();
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
			socket = new Socket(addr, port);
			buffer = new byte[0];
			while (!outQueue.isEmpty())
				send(outQueue.poll());
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
			buffer = new byte[0];
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
