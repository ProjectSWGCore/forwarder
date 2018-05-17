package com.projectswg.forwarder.services.crash;

import com.projectswg.forwarder.intents.client.DataPacketInboundIntent;
import com.projectswg.forwarder.intents.client.DataPacketOutboundIntent;
import com.projectswg.forwarder.intents.control.ClientCrashedIntent;
import com.projectswg.forwarder.resources.recording.PacketRecorder;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.Service;
import me.joshlarson.jlcommon.log.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class PacketRecordingService extends Service {
	
	private Path recorderPath;
	private PacketRecorder recorder;
	
	public PacketRecordingService() {
		this.recorder = null;
	}
	
	@Override
	public boolean initialize() {
		try {
			recorderPath = Files.createTempFile("HolocorePackets", ".hcap");
			recorder = new PacketRecorder(recorderPath.toFile());
		} catch (IOException e) {
			Log.a(e);
			return false;
		}
		return true;
	}
	
	@Override
	public boolean terminate() {
		try {
			if (recorder != null)
				recorder.close();
			return recorderPath.toFile().delete();
		} catch (IOException e) {
			Log.w(e);
			return false;
		}
	}
	
	@IntentHandler
	private void handleClientCrashedIntent(ClientCrashedIntent cci) {
		try {
			byte[] data = Files.readAllBytes(recorderPath);
			ZipEntry entry = new ZipEntry("packet_log.hcap");
			entry.setTime(System.currentTimeMillis());
			entry.setSize(data.length);
			entry.setMethod(ZipOutputStream.DEFLATED);
			synchronized (cci.getFileMutex()) {
				cci.getOutputStream().putNextEntry(entry);
				cci.getOutputStream().write(data);
				cci.getOutputStream().closeEntry();
			}
		} catch (IOException e) {
			Log.w("Failed to write packet data to crash log - IOException");
			Log.w(e);
		}
	}
	
	@IntentHandler
	private void handleDataPacketInboundIntent(DataPacketInboundIntent dpii) {
		recorder.record(false, dpii.getData());
	}
	
	@IntentHandler
	private void handleDataPacketOutboundIntent(DataPacketOutboundIntent dpoi) {
		recorder.record(true, dpoi.getData());
	}
	
}
