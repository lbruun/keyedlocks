/*
 * Copyright (c) 2023 lbruun.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lbruun.util.concurrent.locks;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-key locking mechanism. An instance of this class is a factory for locks for a given object type, {@code T}.
 *
 * <p>
 * Locks are obtained based on a key so that only keys of the same value (object value equality) will compete for the
 * same lock. This can allow for a fine-grained locking mechanism. The implementation guarantees that for a given value
 * of {@code key}, any such operation guarded by a {@code LockToken} from this class, will never overlap in time.
 *
 * <p>
 * Locks are based on {@link ReentrantLock} but the lock itself is never exposed by this class. Instead, a successful
 * acquisition of a lock returns a {@link LockToken token} which can be used to unlock.
 *
 * <p>
 * An instance of this class is fully thread-safe. Indeed, it is meant to be shared between threads and is likely (for a
 * given type, {@code T}) to only be created once in the application's lifetime.
 *
 * <p>
 * Implementation details: If an <i>active lock</i> is defined as one which is held and potentially has waiters then an
 * instance of this class is effectively a cache of active locks, one per key value. Locks will be created on-demand and
 * will automatically leave the cache when they are no longer active. This has two implications:  (1) the cache is
 * likely to be empty most of the time and (2) re-creation of locks will likely happen often. Re-creating is not a
 * problem as creating a lock (in this case a {@link ReentrantLock}) is a very inexpensive operation. The purpose of the
 * cache is not to re-use objects which are costly to create (which is the typical purpose for a cache) but to guarantee
 * singularity.
 *
 * <p>
 * Usage:
 * <pre>{@code
 * KeyedLocks<Animal> animalLocks = KeyedLock.create(Animal.class);
 *
 *
 *
 * // Option 1: Using try-with-resources
 *
 * try (LockToken lockToken = animalLocks.lock(someAnimal)) {
 *   // Perform action guarded by the lock
 *
 * }  // lock is auto-released
 *
 *
 *
 *
 * // Option 2: Using classic lock-and-finally-unlock
 *
 * LockToken lockToken = animalLocks.lock(someAnimal);
 * try {
 *   // Perform action guarded by the lock
 *
 * } finally {
 *     lockToken.unlock();
 * }
 *
 * }</pre>
 *
 * @param <T> key type
 * @see ReentrantLock
 */
public abstract class KeyedLocks<T> {

    // Cache which holds the locks, one per key.
    // Most of the time the map will probably be empty. Entries are auto-removed
    // when their concurrent usage-count is 0. This is what stops the map from growing indefinitely.
    // It is important that changes to the usage count happens _inside_ an atomic action
    // on the map.
    private final ConcurrentHashMap<T, LockWrapper<T>> locks = new ConcurrentHashMap<>();

    private final boolean fair;

    private KeyedLocks(boolean fair) {
        this.fair = fair;
    }


    /**
     * Creates a factory for locks for keys of type {@code keyType} with a given fairness policy for the locks.
     *
     * <p>
     * The {@code fair} parameter controls the fairness policy of the {@link ReentrantLock}s created by the class. If
     * {@code true}, the locks will be fair. A fair lock will - when it is under contention - favor the longest-waiting
     * thread. An unfair lock make no such effort. An unfair lock will provide better throughput, but a fair lock will
     * have smaller variances in times to obtain locks and guarantee lack of starvation. More information in
     * {@link ReentrantLock}.
     *
     * @param keyType type of key allowed in the factory
     * @param fair    fairness policy
     * @see ReentrantLock
     */
    public static final <T> KeyedLocks<T> create(Class<T> keyType, boolean fair) {
        return new KeyedLocks<T>(fair) {
        };
    }

    /**
     * Creates a factory for locks for keys of type {@code keyType}.
     *
     * <p>
     * This is equivalent to {@link #create(Class, boolean) create(keyType, false)} which means that the lock will not
     * be fair.
     *
     * @param keyType type of key allowed in the factory
     * @see ReentrantLock
     */
    public static final <T> KeyedLocks<T> create(Class<T> keyType) {
        return create(keyType, false);
    }

    /**
     * Acquires a lock specific to objects which equals the supplied instance, {@code key}.
     *
     * <p>
     * The method has similar characteristics as {@link ReentrantLock#lock()}.
     *
     * @param key the value to obtain a lock for (must be non-null)
     * @return token which must be used to unlock
     * @throws NullPointerException if {@code key} is null
     */
    public final LockToken<T> lock(T key) {
        LockWrapper<T> lockWrapper = getLockWrapper(key);
        lockWrapper.lock.lock();
        return new LockToken<>(lockWrapper);
    }

    /**
     * Acquires a lock specific to objects which equals the supplied instance, {@code key},
     * but allows the current thread to be interruped while waiting for the lock.
     *
     * <p>
     * The method has similar characteristics as {@link ReentrantLock#lockInterruptibly()}.
     *
     * @param key the value to obtain a lock for (must be non-null)
     * @return token which must be used to unlock
     * @throws NullPointerException if {@code key} is null
     * @throws InterruptedException if the current thread was interrupted while waiting for the lock to be available.
     */
    public final LockToken<T> lockInterruptibly(T key) throws InterruptedException {
        LockWrapper<T> lockWrapper = getLockWrapper(key);
        try {
            lockWrapper.lock.lockInterruptibly();
            return new LockToken<>(lockWrapper);
        } catch (InterruptedException ex) {
            lockWrapper.purgeCacheEntryIfUnused();
            throw ex;
        }
    }

    /**
     * Acquires a lock specific to objects which equals the supplied instance, {@code key}, if the lock is free within
     * the given time limit and the current thread has not been interrupted.
     *
     * <p>
     * The method has similar characteristics as {@link ReentrantLock#tryLock(long, TimeUnit)}.
     *
     * <p>
     * Usage:
     * <pre>{@code
     * KeyedLocks<Animal> animalLocks = KeyedLock.create(Animal.class);
     * Optional<KeyedLock.LockToken<Animal>> lockTokenOpt = animalLocks.tryLock(key, 10, TimeUnit.SECONDS);
     * if (lockTokenOpt.isPresent()) {
     *     try (KeyedLock.LockToken<String> lockToken = lockTokenOpt.get()) {
     *         // perform action guarded by the lock
     *
     *     }
     * } else {
     *     // Could not obtain lock within time limit.
     * }
     * }</pre>
     *
     * @param key      the value to obtain a lock for (must be non-null)
     * @param time     the maximum time to wait for the lock
     * @param timeUnit the time unit of the {@code time} argument (must be non-null)
     * @return token which must be used to unlock (empty if the lock could not be acquired within the time limit)
     * @throws InterruptedException if the current thread was interrupted while waiting for the lock to be available
     * @throws NullPointerException if {@code key} is null or {@code timeUnit} is null
     */
    public final Optional<LockToken<T>> tryLock(T key, long time, TimeUnit timeUnit) throws InterruptedException {

        LockWrapper<T> lockWrapper = getLockWrapper(key);
        Objects.requireNonNull(timeUnit, "timeUnit must be non-null");

        try {
            if (lockWrapper.lock.tryLock(time, timeUnit)) {
                return Optional.of(new LockToken<>(lockWrapper));
            } else {
                // Lock could not be acquired within the time limit. The lock is not held by us.
                // We are now no longer a current "consumer" of this lock so decrement
                // usage count and ultimately remove the lock from the cache.
                lockWrapper.purgeCacheEntryIfUnused();
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            lockWrapper.purgeCacheEntryIfUnused();
            throw e;
        }
    }

    // For testing purpose
    protected ConcurrentHashMap<T, LockWrapper<T>> getMap() {
        return locks;
    }

    // For testing purpose
    protected boolean containsKey(T key) {
        return locks.containsKey(key);
    }

    private LockWrapper<T> getLockWrapper(T key) {
        Objects.requireNonNull(key, "key must be non-null");
        return locks.compute(key, (k, v) -> {
            LockWrapper<T> lw = (v == null) ? new LockWrapper<>(k, this, fair) : v;
            return lw.incrementConcurrentUsageCount();
        });
    }


    protected static final class LockWrapper<T> {
        private final ReentrantLock lock;
        private final AtomicInteger concurrentUsageCount = new AtomicInteger(0);
        private final T key;
        private final KeyedLocks<T> parent;

        private LockWrapper(T key, KeyedLocks<T> parent, boolean fair) {
            this.key = key;
            this.parent = parent;
            this.lock = new ReentrantLock(fair);
        }

        // For testing purpose.
        int getUsageCount() {
            return concurrentUsageCount.get();
        }

        private void purgeCacheEntryIfUnused() {
            parent.locks.computeIfPresent(key, (k, v) -> {
                if (v == this && decrementConcurrentUsageCount() == 0) {
                    return null; // remove from cache
                }
                return v;
            });
        }

        private LockWrapper<T> incrementConcurrentUsageCount() {
            concurrentUsageCount.incrementAndGet();
            return this;
        }

        private int decrementConcurrentUsageCount() {
            return concurrentUsageCount.decrementAndGet();
        }
    }

    /**
     * Token representing an acquired lock. Use the token to unlock the lock when the lock hold is no longer needed.
     * The token has no other purpose than this.
     *
     * <p>
     * The token is for one-time use and must not be shared between threads.
     */
    public static final class LockToken<T> implements AutoCloseable {
        private final LockWrapper<T> parent;
        private volatile boolean isReleased = false;

        private LockToken(LockWrapper<T> parent) {
            this.parent = parent;
        }

        /**
         * Releases the lock.
         *
         * <p>
         * Similar to {@link ReentrantLock#unlock()} except that it cannot throw {@link IllegalMonitorStateException} as
         * a given LockToken implies a lock successfully acquired. (as long as this method is called on the same
         * thread which acquired the {@code LockToken} object instance .. but {@LockToken}s should not be passed
         * between threads)
         *
         * @throws IllegalStateException if the method is called more than once
         */
        public void unlock() {
            if (isReleased) {
                throw new IllegalStateException("Lock has already been released");
            }
            isReleased = true;
            parent.lock.unlock();
            parent.purgeCacheEntryIfUnused();
        }

        /**
         * Releases the lock. Is exactly similar to {@link #unlock()}, but allows to use try-with-resources syntax.
         *
         * @throws IllegalStateException if the method is called more than once
         */
        @Override
        public void close() {
            unlock();
        }
    }
}

