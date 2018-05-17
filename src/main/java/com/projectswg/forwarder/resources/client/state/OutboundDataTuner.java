package com.projectswg.forwarder.resources.client.state;

import me.joshlarson.jlcommon.log.Log;

public class OutboundDataTuner {
	
	private long start;
	private int maxSend;
	private int outOfOrders;
	
	public OutboundDataTuner() {
		reset();
	}
	
	public int getMaxSend() {
		return maxSend;
	}
	
	public void markStart() {
		reset();
		start = System.nanoTime();
	}
	
	public void markEnd() {
		long time = System.nanoTime() - start;
		Log.d("Max Send=%d  Time to Zone=%.0fms  OOO=%d", maxSend, time/1E6, outOfOrders);
		reset();
	}
	
	public void markOOO() {
		outOfOrders++;
		maxSend -= 2;
		capMaxSend();
	}
	
	public void markAck() {
		maxSend += 2;
		capMaxSend();
	}
	
	public void markLag() {
		maxSend -= 50;
		capMaxSend();
	}
	
	private void reset() {
		start = 0;
		outOfOrders = 0;
		maxSend = 200;
	}
	
	private void capMaxSend() {
		if (maxSend < 100)
			maxSend = 100;
		else if (maxSend > 5000)
			maxSend = 5000;
	}
	
}
