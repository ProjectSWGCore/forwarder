package com.projectswg.recording;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;
import java.util.TreeMap;

import com.projectswg.common.debug.Assert;

public class PacketRecorder implements AutoCloseable, Closeable {
	
	private static final byte VERSION = 1;
	
	private final DataOutputStream dataOut;
	private final OutputStream out;
	
	public PacketRecorder(File file) throws FileNotFoundException {
		out = new FileOutputStream(file);
		dataOut = new DataOutputStream(out);
		writeHeader();
	}
	
	public void close() throws IOException {
		dataOut.close();
	}
	
	public void record(boolean server, InetSocketAddress source, InetSocketAddress destination, byte [] data) {
		synchronized (dataOut) {
			try {
				dataOut.writeByte(server?1:0);
				dataOut.writeLong(System.currentTimeMillis());
				recordSocketAddress(source);
				recordSocketAddress(destination);
				dataOut.writeShort(data.length);
				dataOut.write(data);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	private void recordSocketAddress(InetSocketAddress addr) throws IOException {
		Assert.notNull(addr);
		Assert.test(!addr.isUnresolved());
		byte [] raw = addr.getAddress().getAddress();
		dataOut.writeByte(raw.length);
		dataOut.write(raw);
		dataOut.writeShort(addr.getPort());
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
		TimeZone tz = TimeZone.getDefault();
		Map<String, String> systemStrings = new TreeMap<>();
		systemStrings.put("os.arch",			os.getArch());
		systemStrings.put("os.details",			os.getName()+":"+os.getVersion());
		systemStrings.put("os.processor_count", Integer.toString(os.getAvailableProcessors()));
		systemStrings.put("java.version",		System.getProperty("java.version"));
		systemStrings.put("java.vendor",		System.getProperty("java.vendor"));
		systemStrings.put("time.time_zone",		tz.getID()+":"+tz.getDisplayName());
		systemStrings.put("time.current_time",	Long.toString(System.currentTimeMillis()));
		try {
			dataOut.writeByte(systemStrings.size()); // Count of strings
			for (Entry<String, String> e : systemStrings.entrySet())
				dataOut.writeUTF(e.getKey() + "=" + e.getValue());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
}
