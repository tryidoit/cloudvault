package com.cloudvault.backup;

import com.cloudvault.CloudVault;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Single background worker; lower priority number runs first, FIFO within a priority. */
public class BackupQueue {
	private record Task(int priority, long seq, String key, Runnable run) implements Comparable<Task> {
		public int compareTo(Task o) {
			int c = Integer.compare(priority, o.priority);
			return c != 0 ? c : Long.compare(seq, o.seq);
		}
	}

	private final PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
	private final Set<String> queuedKeys = ConcurrentHashMap.newKeySet();
	private final AtomicLong seq = new AtomicLong();
	private volatile boolean running = true;
	private final java.util.concurrent.atomic.AtomicInteger outstanding = new java.util.concurrent.atomic.AtomicInteger(); // queued + running
	private final Thread worker;

	public BackupQueue() {
		worker = new Thread(this::loop, "CloudVault-Worker");
		worker.setDaemon(true); // never keeps Minecraft alive
		worker.setPriority(Thread.MIN_PRIORITY + 1); // background work yields to the game
		worker.start();
	}

	/** @param key de-duplication key (null = always queue). A task whose key is already queued is dropped. */
	public boolean submit(int priority, String key, Runnable task) {
		if (key != null && !queuedKeys.add(key)) return false;
		outstanding.incrementAndGet();
		queue.add(new Task(priority, seq.incrementAndGet(), key, task));
		return true;
	}

	/** Used at game shutdown: wait (bounded) so a just-queued leave backup can finish. Never waits forever. */
	public void awaitIdle(long timeoutMs) {
		long end = System.currentTimeMillis() + timeoutMs;
		while (outstanding.get() > 0 && System.currentTimeMillis() < end) {
			try { Thread.sleep(100); } catch (InterruptedException e) { return; }
		}
	}

	public int size() { return queue.size(); }

	private void loop() {
		while (running) {
			try {
				Task t = queue.take();
				if (t.key != null) queuedKeys.remove(t.key);
				try {
					t.run.run();
				} catch (Throwable e) { // a failing job must never kill the worker or Minecraft
					CloudVault.LOGGER.error("CloudVault job failed: {}", e.toString());
				} finally {
					outstanding.decrementAndGet();
				}
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	public void shutdown() { running = false; worker.interrupt(); }
}
