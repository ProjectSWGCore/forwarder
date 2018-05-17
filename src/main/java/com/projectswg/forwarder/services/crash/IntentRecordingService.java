package com.projectswg.forwarder.services.crash;

import com.projectswg.forwarder.Forwarder.ForwarderData;
import com.projectswg.forwarder.intents.client.ClientConnectedIntent;
import com.projectswg.forwarder.intents.client.ClientDisconnectedIntent;
import com.projectswg.forwarder.intents.client.UpdateStackIntent;
import com.projectswg.forwarder.intents.control.ClientCrashedIntent;
import com.projectswg.forwarder.intents.control.StartForwarderIntent;
import com.projectswg.forwarder.intents.control.StopForwarderIntent;
import com.projectswg.forwarder.intents.server.ServerConnectedIntent;
import com.projectswg.forwarder.intents.server.ServerDisconnectedIntent;
import com.projectswg.forwarder.resources.networking.data.ProtocolStack;
import me.joshlarson.jlcommon.control.Intent;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.Service;
import me.joshlarson.jlcommon.log.Log;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class IntentRecordingService extends Service {
	
	private final DateTimeFormatter dateTimeFormatter;
	
	private Path logPath;
	private FileWriter logWriter;
	private ForwarderData data;
	
	public IntentRecordingService() {
		this.dateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yy HH:mm:ss.SSS zzz").withZone(ZoneId.systemDefault());
		this.logPath = null;
		this.logWriter = null;
		this.data = null;
	}
	
	@Override
	public boolean initialize() {
		try {
			logPath = Files.createTempFile("HolocoreIntents", ".txt");
			logWriter = new FileWriter(logPath.toFile());
		} catch (IOException e) {
			Log.a(e);
			return false;
		}
		return true;
	}
	
	@Override
	public boolean terminate() {
		try {
			if (logWriter != null)
				logWriter.close();
			if (logPath != null)
				return logPath.toFile().delete();
		} catch (IOException e) {
			Log.w(e);
			return false;
		}
		return true;
	}
	
	@IntentHandler
	private void handleClientConnectedIntent(ClientConnectedIntent cci) {
		ForwarderData data = this.data;
		if (data == null)
			log(cci, "");
		else
			log(cci, "Address='%s' Username='%s' Login='%d' Zone='%d'", data.getAddress(), data.getUsername(), data.getLoginPort(), data.getZonePort());
	}
	
	@IntentHandler
	private void handleClientDisconnectedIntent(ClientDisconnectedIntent cdi) {
		log(cdi, "");
	}
	
	@IntentHandler
	private void handleUpdateStackIntent(UpdateStackIntent usi) {
		ProtocolStack stack = usi.getStack();
		if (stack == null)
			log(usi, "Stack='null'");
		else
			log(usi, "Server='%s' Source='%s' ConnectionId='%d'", stack.getServer(), stack.getSource(), stack.getConnectionId());
	}
	
	@IntentHandler
	private void handleStartForwarderIntent(StartForwarderIntent sfi) {
		this.data = sfi.getData();
	}
	
	@IntentHandler
	private void handleStopForwarderIntent(StopForwarderIntent sfi) {
		ForwarderData data = this.data;
		if (data == null)
			log(sfi, "");
		else
			log(sfi, "Address='%s' Username='%s' Login='%d' Zone='%d'", data.getAddress(), data.getUsername(), data.getLoginPort(), data.getZonePort());
	}
	
	@IntentHandler
	private void handleServerConnectedIntent(ServerConnectedIntent sci) {
		ForwarderData data = this.data;
		if (data == null)
			log(sci, "");
		else
			log(sci, "Address='%s' Username='%s' Login='%d' Zone='%d'", data.getAddress(), data.getUsername(), data.getLoginPort(), data.getZonePort());
	}
	
	@IntentHandler
	private void handleServerDisconnectedIntent(ServerDisconnectedIntent sdi) {
		log(sdi, "");
	}
	
	@IntentHandler
	private void handleClientCrashedIntent(ClientCrashedIntent cci) {
		log(cci, "");
		try {
			logWriter.flush();
			byte[] data = Files.readAllBytes(logPath);
			ZipEntry entry = new ZipEntry("log.txt");
			entry.setTime(System.currentTimeMillis());
			entry.setSize(data.length);
			entry.setMethod(ZipOutputStream.DEFLATED);
			synchronized (cci.getFileMutex()) {
				cci.getOutputStream().putNextEntry(entry);
				cci.getOutputStream().write(data);
				cci.getOutputStream().closeEntry();
			}
		} catch (IOException e) {
			Log.w("Failed to write intent data to crash log - IOException");
			Log.w(e);
		}
	}
	
	private synchronized void log(Intent i, String message, Object ... args) {
		try {
			logWriter.write(dateTimeFormatter.format(Instant.now()) + ": " + i.getClass().getSimpleName() + ' ' + String.format(message, args) + '\r' + '\n');
		} catch (IOException e) {
			Log.e("Failed to write to intent log");
		}
	}
	
}
