package com.projectswg.concurrency;

import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class SmartLock {
	
	private final Lock lock;
	private final Condition condition;
	
	public SmartLock() {
		this.lock = new ReentrantLock(true);
		this.condition = lock.newCondition();
	}
	
	public void lock() {
		lock.lock();
	}
	
	public void lockInterruptibly() throws InterruptedException {
		lock.lockInterruptibly();
	}
	
	public boolean tryLock() {
		return lock.tryLock();
	}
	
	public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
		return lock.tryLock(time, unit);
	}
	
	public void unlock() {
		lock.unlock();
	}
	
	@SuppressFBWarnings(value="WA_AWAIT_NOT_IN_LOOP", justification="It's up to the usage to do this")
	public void await() throws InterruptedException {
		lock();
		try {
			condition.await();
		} finally {
			unlock();
		}
	}
	
	@SuppressFBWarnings(value="WA_AWAIT_NOT_IN_LOOP", justification="It's up to the usage to do this")
	public void awaitUninterruptibly() {
		lock();
		try {
			condition.awaitUninterruptibly();
		} finally {
			unlock();
		}
	}
	
	public long awaitNanos(long nanosTimeout) throws InterruptedException {
		lock();
		try {
			return condition.awaitNanos(nanosTimeout);
		} finally {
			unlock();
		}
	}
	
	public boolean await(long time, TimeUnit unit) throws InterruptedException {
		lock();
		try {
			return condition.await(time, unit);
		} finally {
			unlock();
		}
	}
	
	public boolean awaitUntil(Date deadline) throws InterruptedException {
		lock();
		try {
			return condition.awaitUntil(deadline);
		} finally {
			unlock();
		}
	}
	
	public void signal() {
		lock();
		try {
			condition.signal();
		} finally {
			unlock();
		}
	}
	
	public void signalAll() {
		lock();
		try {
			condition.signalAll();
		} finally {
			unlock();
		}
	}
	
}
