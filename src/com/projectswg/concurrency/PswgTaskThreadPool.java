package com.projectswg.concurrency;

import java.util.ArrayDeque;
import java.util.Queue;

public class PswgTaskThreadPool<T> extends PswgThreadPool {
	
	private final Queue<T> tasks;
	private final Runnable runner;
	
	public PswgTaskThreadPool(int nThreads, String namePattern, TaskExecutor<T> executor) {
		super(nThreads, namePattern);
		this.tasks = new ArrayDeque<>();
		this.runner = () -> {
			T t = null;
			synchronized (tasks) {
				t = tasks.poll();
			}
			if (t != null)
				executor.run(t);
		};
	}
	
	@Override
	public void execute(Runnable runnable) {
		throw new UnsupportedOperationException("Runnable are posted automatically by addTask!");
	}
	
	public void addTask(T t) {
		synchronized (tasks) {
			tasks.add(t);
		}
		super.execute(runner);
	}
	
	public interface TaskExecutor<T> {
		void run(T t);
	}
	
}
