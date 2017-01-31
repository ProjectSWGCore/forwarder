package com.projectswg.concurrency;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectswg.control.Assert;

public class PswgThreadPool {
	
	private final AtomicBoolean running;
	private final int nThreads;
	private final ThreadFactory threadFactory;
	private ExecutorService executor;
	
	public PswgThreadPool(int nThreads, String nameFormat) {
		this.running = new AtomicBoolean(false);
		this.nThreads = nThreads;
		this.threadFactory = new CustomThreadFactory(nameFormat);
		this.executor = null;
	}
	
	public void start() {
		Assert.test(!running.getAndSet(true));
		executor = Executors.newFixedThreadPool(nThreads, threadFactory);
	}
	
	public void stop() {
		Assert.test(running.getAndSet(false));
		executor.shutdownNow();
	}
	
	public void execute(Runnable runnable) {
		Assert.test(running.get());
		executor.execute(() -> {
			try {
				runnable.run();
			} catch (Throwable t) {
				t.printStackTrace();
			}
		});
	}
	
	public boolean awaitTermination(long time) {
		Assert.notNull(executor);
		try {
			return executor.awaitTermination(time, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			return false;
		}
	}
	
	public boolean isRunning() {
		return running.get();
	}
	
	private static class CustomThreadFactory implements ThreadFactory {
		
		private final String pattern;
		private int counter;
		
		public CustomThreadFactory(String pattern) {
			this.pattern = pattern;
			this.counter = 0;
		}
		
		@Override
		public Thread newThread(Runnable r) {
			String name;
			if (pattern.contains("%d"))
				name = String.format(pattern, counter++);
			else
				name = pattern;
			return new Thread(r, name);
		}
		
	}
	
}
