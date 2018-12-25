package com.projectswg.forwarder.resources.recording;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

public class PacketRecorder implements AutoCloseable, Closeable {
	
	private static final byte VERSION = 3;
	
	private final DataOutputStream dataOut;
	
	public PacketRecorder(File file) throws FileNotFoundException {
		dataOut = new DataOutputStream(new FileOutputStream(file));
		writeHeader();
	}
	
	public void close() throws IOException {
		dataOut.close();
	}
	
	public void record(boolean server, byte [] data) {
		synchronized (dataOut) {
			try {
				dataOut.writeBoolean(server);
				dataOut.writeLong(System.currentTimeMillis());
				dataOut.writeShort(data.length);
				dataOut.write(data);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	private void writeHeader() {
		try {
			dataOut.writeByte(VERSION);
			writeSystemHeader();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private void writeSystemHeader() {
		OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
		Map<String, String> systemStrings = new TreeMap<>();
		systemStrings.put("os.arch",			os.getArch());
		systemStrings.put("os.details",			os.getName()+":"+os.getVersion());
		systemStrings.put("os.processor_count", Integer.toString(os.getAvailableProcessors()));
		systemStrings.put("java.version",		System.getProperty("java.version"));
		systemStrings.put("java.vendor",		System.getProperty("java.vendor"));
		systemStrings.put("time.time_zone",		ZoneId.systemDefault().getId());
		systemStrings.put("time.current_time",	Instant.now().toString());
		try {
			dataOut.writeByte(systemStrings.size()); // Count of strings
			for (Entry<String, String> e : systemStrings.entrySet())
				dataOut.writeUTF(e.getKey() + "=" + e.getValue());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
}
