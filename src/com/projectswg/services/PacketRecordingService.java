package com.projectswg.services;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectswg.control.Intent;
import com.projectswg.control.Service;
import com.projectswg.intents.ClientConnectionChangedIntent;
import com.projectswg.intents.ClientToServerPacketIntent;
import com.projectswg.intents.ServerConnectionChangedIntent;
import com.projectswg.intents.ServerToClientPacketIntent;
import com.projectswg.recording.PacketRecorder;
import com.projectswg.resources.ClientConnectionStatus;
import com.projectswg.resources.ServerConnectionStatus;

public class PacketRecordingService extends Service {
	
	private final AtomicBoolean recording;
	
	private File recorderFile;
	private PacketRecorder recorder;
	private ClientConnectionStatus clientStatus;
	private ServerConnectionStatus serverStatus;
	private InetSocketAddress source;
	private InetSocketAddress destination;
	
	public PacketRecordingService() {
		this.recording = new AtomicBoolean(false);
		recorderFile = null;
		recorder = null;
		clientStatus = ClientConnectionStatus.DISCONNECTED;
		serverStatus = ServerConnectionStatus.DISCONNECTED;
		source = null;
		destination = null;
	}
	
	@Override
	public boolean initialize() {
		registerForIntent(ServerToClientPacketIntent.TYPE);
		registerForIntent(ClientToServerPacketIntent.TYPE);
		registerForIntent(ClientConnectionChangedIntent.TYPE);
		registerForIntent(ServerConnectionChangedIntent.TYPE);
		return super.initialize();
	}
	
	@Override
	public void onIntentReceived(Intent i) {
		switch (i.getType()) {
			case ServerToClientPacketIntent.TYPE:
				if (i instanceof ServerToClientPacketIntent)
					onServerToClient((ServerToClientPacketIntent) i);
				break;
			case ClientToServerPacketIntent.TYPE:
				if (i instanceof ClientToServerPacketIntent)
					onClientToServer((ClientToServerPacketIntent) i);
				break;
			case ClientConnectionChangedIntent.TYPE:
				if (i instanceof ClientConnectionChangedIntent)
					onClientStatusChanged((ClientConnectionChangedIntent) i);
				break;
			case ServerConnectionChangedIntent.TYPE:
				if (i instanceof ServerConnectionChangedIntent)
					onServerStatusChanged((ServerConnectionChangedIntent) i);
				break;
		}
	}
	
	private void startRecording() {
		if (recording.getAndSet(true))
			return;
		try {
			recorderFile = File.createTempFile("HolocoreRecording", ".hcap");
			recorder = new PacketRecorder(recorderFile);
		} catch (IOException e) {
			e.printStackTrace();
			recorderFile = null;
			recorder = null;
			recording.set(false);
		}
	}
	
	private void stopRecording() {
		if (!recording.getAndSet(false))
			return;
		try {
			recorder.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private void onClientStatusChanged(ClientConnectionChangedIntent ccci) {
		this.clientStatus = ccci.getStatus();
		updateRecordingState();
	}
	
	private void onServerStatusChanged(ServerConnectionChangedIntent scci) {
		this.serverStatus = scci.getStatus();
		this.source = scci.getSource();
		this.destination = scci.getDestination();
		updateRecordingState();
	}
	
	private void updateRecordingState() {
		if (clientStatus != ClientConnectionStatus.DISCONNECTED && serverStatus == ServerConnectionStatus.CONNECTING)
			startRecording();
		else if (clientStatus == ClientConnectionStatus.DISCONNECTED)
			stopRecording();
	}
	
	private void onServerToClient(ServerToClientPacketIntent s2c) {
		if (!recording.get())
			return;
		recorder.record(true, destination, source, s2c.getRawData());
	}
	
	private void onClientToServer(ClientToServerPacketIntent c2s) {
		if (!recording.get())
			return;
		recorder.record(false, source, destination, c2s.getData());
	}
	
}
