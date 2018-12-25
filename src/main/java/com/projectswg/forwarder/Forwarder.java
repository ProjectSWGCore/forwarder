package com.projectswg.forwarder;

import com.projectswg.forwarder.intents.client.ClientConnectedIntent;
import com.projectswg.forwarder.intents.client.ClientDisconnectedIntent;
import com.projectswg.forwarder.intents.control.ClientCrashedIntent;
import com.projectswg.forwarder.intents.control.StartForwarderIntent;
import com.projectswg.forwarder.intents.control.StopForwarderIntent;
import me.joshlarson.jlcommon.concurrency.Delay;
import me.joshlarson.jlcommon.control.IntentManager;
import me.joshlarson.jlcommon.control.IntentManager.IntentSpeedStatistics;
import me.joshlarson.jlcommon.control.Manager;
import me.joshlarson.jlcommon.control.SafeMain;
import me.joshlarson.jlcommon.log.Log;
import me.joshlarson.jlcommon.log.Log.LogLevel;
import me.joshlarson.jlcommon.log.log_wrapper.ConsoleLogWrapper;
import me.joshlarson.jlcommon.utilities.ThreadUtilities;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Forwarder {
	
	private final ForwarderData data;
	private final IntentManager intentManager;
	private final AtomicBoolean connected;
	
	public Forwarder() {
		this.data = new ForwarderData();
		this.intentManager = new IntentManager(false, Runtime.getRuntime().availableProcessors());
		this.connected = new AtomicBoolean(false);
	}
	
	public static void main(String [] args) {
		SafeMain.main("forwarder", Forwarder::mainRunnable);
	}
	
	private static void mainRunnable() {
		Log.addWrapper(new ConsoleLogWrapper(LogLevel.TRACE));
		Forwarder forwarder = new Forwarder();
		forwarder.getData().setAddress(new InetSocketAddress(44463));
		forwarder.run();
		ThreadUtilities.printActiveThreads();
	}
	
	public File readClientOutput(InputStream is) {
		StringBuilder output = new StringBuilder();
		byte [] buffer = new byte[2048];
		int n;
		try {
			while ((n = is.read(buffer)) > 0) {
				output.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			Log.w("IOException while reading client output");
			Log.w(e);
		}
		return onClientClosed(output.toString());
	}
	
	private File onClientClosed(String clientOutput) {
		if (!connected.get())
			return null;
		File output;
		try {
			output = Files.createTempFile("HolocoreCrashLog", ".zip").toFile();
		} catch (IOException e) {
			Log.e("Failed to write crash log! Could not create temp file.");
			return null;
		}
		try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
			{
				byte [] data = clientOutput.getBytes(StandardCharsets.UTF_8);
				ZipEntry entry = new ZipEntry("output.txt");
				entry.setTime(System.currentTimeMillis());
				entry.setSize(data.length);
				entry.setMethod(ZipOutputStream.DEFLATED);
				zip.putNextEntry(entry);
				zip.write(data);
				zip.closeEntry();
			}
			ClientCrashedIntent cci = new ClientCrashedIntent(zip);
			cci.broadcast(intentManager);
			long startSleep = System.nanoTime();
			while (!cci.isComplete() && System.nanoTime() - startSleep < 1E9)
				Delay.sleepMilli(10);
			return output;
		} catch (IOException e) {
			Log.e("Failed to write crash log! %s: %s", e.getClass().getName(), e.getMessage());
			return null;
		}
	}
	
	public void run() {
		intentManager.registerForIntent(ClientConnectedIntent.class, "Forwarder#handleClientConnectedIntent", cci -> connected.set(true));
		intentManager.registerForIntent(ClientDisconnectedIntent.class, "Forwarder#handleClientDisconnectedIntent", cdi -> connected.set(false));
		
		ConnectionManager primary = new ConnectionManager();
		{
			primary.setIntentManager(intentManager);
			List<Manager> managers = Collections.singletonList(primary);
			Manager.start(managers);
			new StartForwarderIntent(data).broadcast(intentManager);
			Manager.run(managers, 100);
			List<IntentSpeedStatistics> intentTimes = intentManager.getSpeedRecorder();
			intentTimes.sort(Comparator.comparingLong(IntentSpeedStatistics::getTotalTime).reversed());
			Log.i("    Intent Times: [%d]", intentTimes.size());
			Log.i("        %-30s%-60s%-40s%-10s%-20s", "Intent", "Receiver Class", "Receiver Method", "Count", "Time");
			for (IntentSpeedStatistics record : intentTimes) {
				String receiverName = record.getKey().toString();
				if (receiverName.indexOf('$') != -1)
					receiverName = receiverName.substring(0, receiverName.indexOf('$'));
				receiverName = receiverName.replace("com.projectswg.forwarder.services.", "");
				String intentName = record.getIntent().getSimpleName();
				String recordCount = Long.toString(record.getCount());
				String recordTime = String.format("%.6fms", record.getTotalTime() / 1E6);
				String [] receiverSplit = receiverName.split("#", 2);
				Log.i("        %-30s%-60s%-40s%-10s%-20s", intentName, receiverSplit[0], receiverSplit[1], recordCount, recordTime);
			}
			new StopForwarderIntent().broadcast(intentManager);
			Manager.stop(managers);
		}
		
		intentManager.close(false, 1000);
		primary.setIntentManager(null);
	}
	
	public ForwarderData getData() {
		return data;
	}
	
	public static class ForwarderData {
		
		private InetSocketAddress address	= null;
		private boolean verifyServer		= true;
		private String username				= null;
		private String password				= null;
		private int loginPort				= 0;
		private int zonePort				= 0;
		private int pingPort				= 0;
		private int outboundTunerMaxSend	= 100;
		private int outboundTunerInterval	= 20;
		
		private ForwarderData() { }
		
		public InetSocketAddress getAddress() {
			return address;
		}
		
		public boolean isVerifyServer() {
			return verifyServer;
		}
		
		public String getUsername() {
			return username;
		}
		
		public String getPassword() {
			return password;
		}
		
		public int getLoginPort() {
			return loginPort;
		}
		
		public int getZonePort() {
			return zonePort;
		}
		
		public int getPingPort() {
			return pingPort;
		}
		
		public int getOutboundTunerMaxSend() {
			return outboundTunerMaxSend;
		}
		
		public int getOutboundTunerInterval() {
			return outboundTunerInterval;
		}
		
		public void setAddress(InetSocketAddress address) {
			this.address = address;
		}
		
		public void setVerifyServer(boolean verifyServer) {
			this.verifyServer = verifyServer;
		}
		
		public void setUsername(String username) {
			this.username = username;
		}
		
		public void setPassword(String password) {
			this.password = password;
		}
		
		public void setLoginPort(int loginPort) {
			this.loginPort = loginPort;
		}
		
		public void setZonePort(int zonePort) {
			this.zonePort = zonePort;
		}
		
		public void setPingPort(int pingPort) {
			this.pingPort = pingPort;
		}
		
		public void setOutboundTunerMaxSend(int outboundTunerMaxSend) {
			this.outboundTunerMaxSend = outboundTunerMaxSend;
		}
		
		public void setOutboundTunerInterval(int outboundTunerInterval) {
			this.outboundTunerInterval = outboundTunerInterval;
		}
		
	}
	
}
