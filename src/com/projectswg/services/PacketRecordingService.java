package com.projectswg.services;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

import com.projectswg.connection.ServerConnectionStatus;
import com.projectswg.control.Assert;
import com.projectswg.control.Service;
import com.projectswg.intents.ClientToServerPacketIntent;
import com.projectswg.intents.ServerConnectionChangedIntent;
import com.projectswg.intents.ServerToClientPacketIntent;
import com.projectswg.recording.PacketRecorder;
import com.projectswg.utilities.Log;

public class PacketRecordingService extends Service {
	
	private final Object recordingMutex;
	
	private File recorderFile;
	private PacketRecorder recorder;
	private ServerConnectionStatus serverStatus;
	private InetSocketAddress source;
	private InetSocketAddress destination;
	private boolean recording;
	
	public PacketRecordingService() {
		this.recordingMutex = new Object();
		this.recorderFile = null;
		this.recorder = null;
		this.serverStatus = ServerConnectionStatus.DISCONNECTED;
		this.source = null;
		this.destination = null;
		this.recording = false;
	}
	
	@Override
	public boolean initialize() {
		registerForIntent(ServerToClientPacketIntent.class, stcpi -> onServerToClient(stcpi));
		registerForIntent(ClientToServerPacketIntent.class, ctspi -> onClientToServer(ctspi));
		registerForIntent(ServerConnectionChangedIntent.class, scci -> onServerStatusChanged(scci));
		return super.initialize();
	}
	
	public void setAddress(InetSocketAddress source, InetSocketAddress destination) {
		this.source = source;
		this.destination = destination;
	}
	
	private void startRecording() {
		synchronized (recordingMutex) {
			Assert.test(!recording);
			recording = true;
			try {
				recorderFile = File.createTempFile("HolocoreRecording", ".hcap");
				recorder = new PacketRecorder(recorderFile);
			} catch (IOException e) {
				Log.err(this, e);
				recorderFile = null;
				recorder = null;
				recording = false;
			}
		}
	}
	
	private void stopRecording() {
		synchronized (recordingMutex) {
			Assert.test(recording);
			recording = false;
			try {
				recorder.close();
			} catch (IOException e) {
				Log.err(this, e);
			}
		}
	}
	
	private void onServerStatusChanged(ServerConnectionChangedIntent scci) {
		this.serverStatus = scci.getStatus();
		updateRecordingState();
	}
	
	private void updateRecordingState() {
		switch (serverStatus) {
			case CONNECTING:
				startRecording();
				break;
			case DISCONNECTED:
				stopRecording();
				break;
			default:
				break;
		}
	}
	
	private void onServerToClient(ServerToClientPacketIntent s2c) {
		synchronized (recordingMutex) {
			if (!recording)
				return;
			recorder.record(true, destination, source, s2c.getRawData());
		}
	}
	
	private void onClientToServer(ClientToServerPacketIntent c2s) {
		synchronized (recordingMutex) {
			if (!recording)
				return;
			recorder.record(false, source, destination, c2s.getData());
		}
	}
	
}
