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
package com.projectswg.forwarder.resources.networking;

import me.joshlarson.jlcommon.concurrency.Delay;
import me.joshlarson.jlcommon.log.Log;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * This class represents a UDP server that listens for packets and
 * will call the callback when it receives one
 */
public class UDPServer {
	
	private final byte [] dataBuffer;
	private final AtomicBoolean running;
	private final InetSocketAddress bindAddr;
	private final Consumer<DatagramPacket> callback;
	
	private DatagramSocket socket;
	private Thread thread;
	
	public UDPServer(@Nonnull InetSocketAddress bindAddr, @Nonnull Consumer<DatagramPacket> callback) {
		this(bindAddr, 1024, callback);
	}
	
	public UDPServer(@Nonnull InetSocketAddress bindAddr, int packetSize, @Nonnull Consumer<DatagramPacket> callback) {
		this.dataBuffer = new byte[packetSize];
		this.running = new AtomicBoolean(false);
		this.bindAddr = bindAddr;
		this.callback = callback;
	}
	
	public void bind() throws SocketException {
		bind(null);
	}
	
	public void bind(Consumer<DatagramSocket> customizationCallback) throws SocketException {
		assert socket == null : "binding twice";
		socket = new DatagramSocket(bindAddr);
		if (customizationCallback != null)
			customizationCallback.accept(socket);
		start();
	}
	
	public void close() {
		assert socket != null : "socket already closed";
		stop();
		socket.close();
		socket = null;
	}
	
	public int getPort() {
		int port = socket.getLocalPort();
		while (port == 0) {
			port = socket.getLocalPort();
			if (!Delay.sleepMilli(5))
				break;
		}
		return port;
	}
	
	public boolean isRunning() {
		return running.get();
	}
	
	public boolean send(DatagramPacket packet) {
		try {
			socket.send(packet);
			return true;
		} catch (IOException e) {
			String msg = e.getMessage();
			if (msg == null || !msg.toLowerCase(Locale.US).contains("socket closed")) {
				Log.e(e);
				close();
			}
		}
		return false;
	}
	
	public boolean send(int port, InetAddress addr, byte [] data) {
		return send(new DatagramPacket(data, data.length, addr, port));
	}
	
	public boolean send(int port, String addr, byte [] data) {
		try {
			return send(port, InetAddress.getByName(addr), data);
		} catch (UnknownHostException e) {
			Log.e(e);
		}
		return false;
	}
	
	public boolean send(InetSocketAddress addr, byte [] data) {
		return send(new DatagramPacket(data, data.length, addr));
	}
	
	private void start() {
		running.set(true);
		thread = new Thread(this::run);
		thread.setName("UDPServer Port#" + getPort());
		thread.start();
	}
	
	private void stop() {
		running.set(false);
		thread.interrupt();
	}
	
	private void run() {
		try {
			while (running.get()) {
				DatagramPacket packet = new DatagramPacket(dataBuffer, dataBuffer.length);
				try {
					socket.receive(packet);
					if (packet.getLength() > 0) {
						byte [] buffer = new byte[packet.getLength()];
						System.arraycopy(packet.getData(), 0, buffer, 0, packet.getLength());
						packet.setData(buffer);
						callback.accept(packet);
					}
				} catch (IOException e) {
					String msg = e.getMessage();
					if (msg == null || !msg.toLowerCase(Locale.US).contains("socket closed")) {
						Log.e(e);
						close();
					}
					packet.setLength(0);
				}
			}
		} catch (Exception e) {
			Log.e(e);
		} finally {
			running.set(false);
		}
	}
	
}
