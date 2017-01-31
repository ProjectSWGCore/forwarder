package com.projectswg.concurrency;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class SynchronizedQueue<T> implements Queue<T> {
	
	private final Queue<T> queue;
	
	public SynchronizedQueue() {
		this(new ArrayDeque<>());
	}
	
	public SynchronizedQueue(Queue<T> queue) {
		this.queue = queue;
	}
	
	public synchronized void forEach(Consumer<? super T> action) {
		queue.forEach(action);
	}
	
	public synchronized boolean add(T e) {
		return queue.add(e);
	}
	
	public synchronized boolean offer(T e) {
		return queue.offer(e);
	}
	
	public synchronized int size() {
		return queue.size();
	}
	
	public synchronized boolean isEmpty() {
		return queue.isEmpty();
	}
	
	public synchronized boolean contains(Object o) {
		return queue.contains(o);
	}
	
	public synchronized T remove() {
		return queue.remove();
	}
	
	public synchronized T poll() {
		return queue.poll();
	}
	
	public synchronized T element() {
		return queue.element();
	}
	
	public synchronized Iterator<T> iterator() {
		return queue.iterator();
	}
	
	public synchronized T peek() {
		return queue.peek();
	}
	
	public synchronized Object[] toArray() {
		return queue.toArray();
	}
	
	public synchronized <E> E[] toArray(E[] a) {
		return queue.toArray(a);
	}
	
	public synchronized boolean remove(Object o) {
		return queue.remove(o);
	}
	
	public synchronized boolean containsAll(Collection<?> c) {
		return queue.containsAll(c);
	}
	
	public synchronized boolean addAll(Collection<? extends T> c) {
		return queue.addAll(c);
	}
	
	public synchronized boolean removeAll(Collection<?> c) {
		return queue.removeAll(c);
	}
	
	public synchronized boolean removeIf(Predicate<? super T> filter) {
		return queue.removeIf(filter);
	}
	
	public synchronized boolean retainAll(Collection<?> c) {
		return queue.retainAll(c);
	}
	
	public synchronized void clear() {
		queue.clear();
	}
	
	public synchronized boolean equals(Object o) {
		return queue.equals(o);
	}
	
	public synchronized int hashCode() {
		return queue.hashCode();
	}
	
	public synchronized Spliterator<T> spliterator() {
		return queue.spliterator();
	}
	
	public synchronized Stream<T> stream() {
		return queue.stream();
	}
	
	public synchronized Stream<T> parallelStream() {
		return queue.parallelStream();
	}
	
}
