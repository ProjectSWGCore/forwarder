package com.projectswg.concurrency;

public class PswgBasicScheduledThread extends PswgScheduledThreadPool {
	
	private final Runnable runnable;
	
	public PswgBasicScheduledThread(String name, Runnable runnable) {
		super(1, name);
		this.runnable = runnable;
	}
	
	@Override
	public void start() {
		throw new UnsupportedOperationException("Cannot use this function. Must use startX(initialDelay, periodicDelay)");
	}
	
	public void startWithFixedRate(long initialDelay, long periodicDelay) {
		super.start();
		super.executeWithFixedRate(initialDelay, periodicDelay, runnable);
	}
	
	public void startWithFixedDelay(long initialDelay, long periodicDelay) {
		super.start();
		super.executeWithFixedDelay(initialDelay, periodicDelay, runnable);
	}
	
	@Override
	public void executeWithFixedRate(long initialDelay, long periodicDelay, Runnable runnable) {
		throw new UnsupportedOperationException("Runnable is defined in the constructor!");
	}
	
	@Override
	public void executeWithFixedDelay(long initialDelay, long periodicDelay, Runnable runnable) {
		throw new UnsupportedOperationException("Runnable is defined in the constructor!");
	}
	
}
