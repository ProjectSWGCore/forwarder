package com.projectswg.concurrency;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectswg.control.Assert;

public class PswgScheduledThreadPool {
	
	private final AtomicBoolean running;
	private final int nThreads;
	private final ThreadFactory threadFactory;
	private ScheduledExecutorService executor;
	
	public PswgScheduledThreadPool(int nThreads, String nameFormat) {
		this.running = new AtomicBoolean(false);
		this.nThreads = nThreads;
		this.threadFactory = new CustomThreadFactory(nameFormat);
		this.executor = null;
	}
	
	public void start() {
		Assert.test(!running.getAndSet(true));
		executor = Executors.newScheduledThreadPool(nThreads, threadFactory);
	}
	
	public void stop() {
		Assert.test(running.getAndSet(false));
		executor.shutdownNow();
	}
	
	public void executeWithFixedRate(long initialDelay, long time, Runnable runnable) {
		Assert.test(running.get());
		executor.scheduleAtFixedRate(() -> {
			try {
				runnable.run();
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}, initialDelay, time, TimeUnit.MILLISECONDS);
	}
	
	public void executeWithFixedDelay(long initialDelay, long time, Runnable runnable) {
		Assert.test(running.get());
		executor.scheduleWithFixedDelay(() -> {
			try {
				runnable.run();
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}, initialDelay, time, TimeUnit.MILLISECONDS);
	}
	
	public boolean awaitTermination(long time) {
		Assert.notNull(executor);
		try {
			return executor.awaitTermination(time, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			return false;
		}
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
