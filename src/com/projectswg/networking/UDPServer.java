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
package com.projectswg.networking;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectswg.control.Assert;
import com.projectswg.utilities.Log;
import com.projectswg.utilities.ThreadUtilities;

/**
 * This class represents a UDP server that listens for packets and
 * will call the callback when it receives one
 */
public class UDPServer {
	
	private final Object waitingForPacket;
	private final byte [] dataBuffer;
	private final Queue <DatagramPacket> inbound;
	private final AtomicBoolean running;
	private final int desiredPort;
	
	private DatagramSocket socket;
	private UDPCallback callback;
	private Thread thread;
	
	public UDPServer(int port) throws SocketException {
		this(port, 1024);
	}
	
	public UDPServer(int port, int packetSize) {
		this.waitingForPacket = new Object();
		this.dataBuffer = new byte[packetSize];
		this.inbound = new ArrayDeque<>();
		this.running = new AtomicBoolean(false);
		this.desiredPort = port;
		this.callback = null;
	}
	
	public void bind() throws SocketException {
		Assert.isNull(socket);
		if (desiredPort > 0)
			socket = new DatagramSocket(desiredPort);
		else
			socket = new DatagramSocket();
		start();
	}
	
	public void close() {
		Assert.notNull(socket);
		stop();
		socket.close();
		socket = null;
	}
	
	public DatagramPacket receive() {
		return inbound.poll();
	}
	
	public int packetCount() {
		return inbound.size();
	}
	
	public int getPort() {
		int port = socket.getLocalPort();
		while (port == 0) {
			port = socket.getLocalPort();
			if (!ThreadUtilities.sleep(5))
				break;
		}
		return port;
	}
	
	public boolean isRunning() {
		return running.get();
	}
	
	public void waitForPacket() {
		synchronized (waitingForPacket) {
			try {
				while (inbound.size() == 0) {
					waitingForPacket.wait();
				}
			} catch (InterruptedException e) {
				
			}
		}
	}
	
	public boolean send(DatagramPacket packet) {
		return sendRaw(packet);
	}
	
	public boolean send(int port, InetAddress addr, byte [] data) {
		return send(new DatagramPacket(data, data.length, addr, port));
	}
	
	public boolean send(int port, String addr, byte [] data) {
		try {
			return send(port, InetAddress.getByName(addr), data);
		} catch (UnknownHostException e) {
			Log.err(this, e);
		}
		return false;
	}
	
	public boolean send(InetSocketAddress addr, byte [] data) {
		return send(addr.getPort(), addr.getAddress(), data);
	}
	
	public void setCallback(UDPCallback callback) {
		this.callback = callback;
	}
	
	public void removeCallback() {
		callback = null;
	}
	
	public interface UDPCallback {
		public void onReceivedPacket(DatagramPacket packet);
	}
	
	private void start() {
		Assert.test(!running.getAndSet(true));
		thread = new Thread(() -> run());
		thread.setName("UDPServer Port#" + getPort());
		thread.start();
	}
	
	private void stop() {
		Assert.test(running.getAndSet(false));
		thread.interrupt();
	}
	
	private void run() {
		try {
			while (running.get()) {
				loop();
			}
		} catch (Exception e) {
			Log.err(this, e);
		} finally {
			running.set(false);
		}
	}
	
	private void loop() {
		DatagramPacket packet = receiveRaw();
		if (packet.getLength() <= 0)
			return;
		if (callback != null)
			callback.onReceivedPacket(packet);
		else
			inbound.add(packet);
		notifyPacketReceived();
	}
	
	private void notifyPacketReceived() {
		synchronized (waitingForPacket) {
			waitingForPacket.notifyAll();
		}
	}
	
	private boolean sendRaw(DatagramPacket packet) {
		try {
			socket.send(packet);
			return true;
		} catch (IOException e) {
			String msg = e.getMessage();
			if (msg == null || !msg.toLowerCase(Locale.US).contains("socket closed")) {
				Log.err(this, e);
				close();
			}
		}
		return false;
	}
	
	private DatagramPacket receiveRaw() {
		DatagramPacket packet = new DatagramPacket(dataBuffer, dataBuffer.length);
		try {
			socket.receive(packet);
			byte [] buffer = new byte[packet.getLength()];
			System.arraycopy(packet.getData(), 0, buffer, 0, packet.getLength());
			packet.setData(buffer);
		} catch (IOException e) {
			String msg = e.getMessage();
			if (msg == null || !msg.toLowerCase(Locale.US).contains("socket closed")) {
				Log.err(this, e);
				close();
			}
			packet.setLength(0);
		}
		return packet;
	}
	
}
