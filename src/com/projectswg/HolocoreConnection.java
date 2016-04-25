package com.projectswg;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectswg.Connections.ConnectionCallback;
import com.projectswg.networking.NetInterceptor.InterceptorProperties;
import com.projectswg.resources.HolocorePreferences;
import com.projectswg.utilities.Log;

public class HolocoreConnection {
	
	private static final InetAddress DEFAULT_ADDRESS;
	
	static {
		InetAddress addr;
		try {
			addr = InetAddress.getByName("::1");
		} catch (UnknownHostException e) {
			addr = InetAddress.getLoopbackAddress();
		}
		DEFAULT_ADDRESS = addr;
	}
	
	private final AtomicBoolean running;
	private final InetAddress remoteAddr;
	private final int remotePort;
	private final boolean timeout;
	private int loginPort;
	private Connections connections;
	
	public HolocoreConnection() {
		this(DEFAULT_ADDRESS, 44463, 44453, true);
	}
	
	public HolocoreConnection(InetAddress remoteAddr, int remotePort, int loginPort, boolean timeout) {
		this.remoteAddr = remoteAddr;
		this.remotePort = remotePort;
		this.timeout = timeout;
		this.loginPort = loginPort;
		this.running = new AtomicBoolean(false);
	}
	
	public void start() {
		if (running.getAndSet(true)) {
			Log.err(this, "Not starting, already started!");
			return;
		}
		boolean success = false;
		int attempts = 0;
		while (!success) {
			Log.out(this, "Initializing connections... attempt %d", attempts++);
			connections = new Connections(remoteAddr, remotePort, loginPort, timeout);
			success = connections.initialize() && connections.start();
			if (!success) {
				Log.err(this, "Failed to initialize");
				connections.stop();
				connections.terminate();
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				loginPort++;
			}
		}
		Log.out(this, "Connections initialized.");
		setProperties();
	}
	
	public void stop() {
		if (!running.getAndSet(false)) {
			Log.err(this, "Not stopping, already stopped!");
			return;
		}
		if (connections == null) {
			Log.err(this, "Not stopping, connections is null!");
			return;
		}
		updateProperties();
		connections.stop();
		connections.terminate();
		connections = null;
		Log.out(this, "Connections terminated.");
	}
	
	public void setCallback(ConnectionCallback callback) {
		connections.setCallback(callback);
	}
	
	public boolean setRemote(InetAddress addr, int port) {
		return connections.setRemote(addr, port);
	}
	
	public InetAddress getRemoteAddress() {
		return connections.getRemoteAddress();
	}
	
	public int getRemotePort() {
		return connections.getRemotePort();
	}
	
	public int getLoginPort() {
		return connections.getLoginPort();
	}
	
	public int getZonePort() {
		return connections.getZonePort();
	}
	
	public long getServerToClientCount() {
		return connections.getServerToClientCount();
	}
	
	public long getClientToServerCount() {
		return connections.getClientToServerCount();
	}
	
	public InterceptorProperties getInterceptorProperties() {
		return connections.getInterceptorProperties();
	}
	
	private void setProperties() {
		HolocorePreferences pref = HolocorePreferences.getInstance();
		InterceptorProperties inter = getInterceptorProperties();
		inter.setUsername(pref.getUsername());
		inter.setPassword(pref.getPassword());
	}
	
	private void updateProperties() {
		HolocorePreferences pref = HolocorePreferences.getInstance();
		InterceptorProperties inter = getInterceptorProperties();
		if (!inter.getUsername().isEmpty())
			pref.setUsername(inter.getUsername());
		if (!inter.getPassword().isEmpty())
			pref.setPassword(inter.getPassword());
	}
	
}
