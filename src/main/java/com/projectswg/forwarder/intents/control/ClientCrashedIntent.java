package com.projectswg.forwarder.intents.control;

import me.joshlarson.jlcommon.control.Intent;

import java.util.zip.ZipOutputStream;

public class ClientCrashedIntent extends Intent {
	
	private final ZipOutputStream outputStream;
	private final Object fileMutex;
	
	public ClientCrashedIntent(ZipOutputStream outputStream) {
		this.outputStream = outputStream;
		this.fileMutex = new Object();
	}
	
	public ZipOutputStream getOutputStream() {
		return outputStream;
	}
	
	public Object getFileMutex() {
		return fileMutex;
	}
}
