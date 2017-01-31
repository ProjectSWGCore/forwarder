package com.projectswg.concurrency;

import java.util.concurrent.atomic.AtomicBoolean;

public class PswgBasicThread extends PswgThreadPool {
	
	private final AtomicBoolean executing;
	private final Runnable runnable;
	
	public PswgBasicThread(String name, Runnable runnable) {
		super(1, name);
		this.executing = new AtomicBoolean(false);
		this.runnable = runnable;
	}
	
	@Override
	public void start() {
		super.start();
		super.execute(() -> {
			executing.set(true);
			try {
				runnable.run();
			} finally {
				executing.set(false);
			}
		});
	}
	
	public boolean isExecuting() {
		return executing.get();
	}
	
	@Override
	public void execute(Runnable runnable) {
		throw new UnsupportedOperationException("Runnable is defined in the constructor!");
	}
	
}
