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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class KeyedLocksTest {

    @Test
    @DisplayName("Multi-threaded test")
    void lock() throws InterruptedException, ExecutionException {

        final KeyedLocks<Customer> customerLocks = KeyedLocks.create(Customer.class, true);

        ExecutorService executor = Executors.newCachedThreadPool();

        Customer customerA = new Customer("A");
        Customer customerB = new Customer("B");

        TestTask testTask1 = new TestTask(customerLocks, customerA);
        TestTask testTask2 = new TestTask(customerLocks, customerA);
        TestTask testTask3 = new TestTask(customerLocks, customerB);
        TestTask testTask4 = new TestTask(customerLocks, customerA, 1, TimeUnit.MICROSECONDS);

        Future<?> fut1 = executor.submit(testTask1);
        testTask1.awaitTaskIsExecuting();
        testTask1.awaitLockIsAcquired();

        assertEquals(1, customerLocks.getMap().size());
        assertEquals(1, customerLocks.getMap().get(customerA).getUsageCount());

        Future<?> fut2 = executor.submit(testTask2);
        testTask2.awaitTaskIsExecuting();

        Future<?> fut3 = executor.submit(testTask3);
        testTask3.awaitTaskIsExecuting();

        Future<?> fut4 = executor.submit(testTask4);
        testTask4.awaitTaskIsExecuting();

        // Allow recently started tasks (#2, #3 and #4) to have entered the lock waiting state.
        // (or in the case of #4:  have not been able to acquire lock within the given time but
        //  is still executing).
        Thread.sleep(1000);

        assertEquals(2, customerLocks.getMap().size());
        assertEquals(2, customerLocks.getMap().get(customerA).getUsageCount());
        assertEquals(1, customerLocks.getMap().get(customerB).getUsageCount());


        // Let Task1 finish
        testTask1.taskProgress();
        fut1.get(); // await

        assertEquals(2, customerLocks.getMap().size());
        assertEquals(1, customerLocks.getMap().get(customerA).getUsageCount());
        assertEquals(1, customerLocks.getMap().get(customerB).getUsageCount());


        // Let Task2 finish
        testTask2.taskProgress();
        fut2.get(); // await

        assertEquals(1, customerLocks.getMap().size());
        assertNull(customerLocks.getMap().get(customerA));
        assertEquals(1, customerLocks.getMap().get(customerB).getUsageCount());


        // Let Task3 finish
        testTask3.taskProgress();
        fut3.get();

        // Let Task4 finish
        testTask3.taskProgress();
        fut3.get();

        assertTrue(customerLocks.getMap().isEmpty());
    }

    /**
     * A task which spins until we release it by calling {@code taskProgress}.
     */
    public static class TestTask implements Runnable {
        final CountDownLatch taskIsExecuting = new CountDownLatch(1);
        final CountDownLatch lockIsAcquired = new CountDownLatch(1);
        final CountDownLatch taskProgress = new CountDownLatch(1);
        final Customer key;
        private final KeyedLocks<Customer> customerLocks;
        private final int waitTime;
        private final TimeUnit timeUnit;

        public TestTask(KeyedLocks<Customer> customerLocks, Customer key, int waitTime, TimeUnit timeUnit) {
            this.key = key;
            this.customerLocks = customerLocks;
            this.waitTime = waitTime;
            this.timeUnit = timeUnit;
        }
        public TestTask(KeyedLocks<Customer> customerLocks, Customer key) {
            this(customerLocks, key, -1, TimeUnit.MICROSECONDS);
        }

        @Override
        public void run() {
            this.taskIsExecuting.countDown();

            try {
                if (waitTime == -1) {
                    try (KeyedLocks.LockToken<Customer> lt = customerLocks.lock(key)) {
                        lockIsAcquired.countDown();
                        taskProgress.await();
                    }
                } else {
                    Optional<KeyedLocks.LockToken<Customer>> lockTokenOpt = customerLocks.tryLock(key, waitTime, timeUnit);
                    if (lockTokenOpt.isPresent()) {
                        try (KeyedLocks.LockToken<Customer> lockWrapper = lockTokenOpt.get()) {
                            lockIsAcquired.countDown();
                            taskProgress.await();
                        }
                    } else {
                        taskProgress.await();
                    }
                }
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }

        public void awaitTaskIsExecuting() throws InterruptedException {
            taskIsExecuting.await();
        }
        public void awaitLockIsAcquired() throws InterruptedException {
            lockIsAcquired.await();
        }

        public void taskProgress() {
            taskProgress.countDown();
        }
    }

    /**
     * Used as the key type. Could be any type of object with a reasonable
     * equals() and hash() implementation.
     */
    public static class Customer {
        private final String customerId;

        public Customer(String customerId) {
            this.customerId = customerId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Customer customer = (Customer) o;
            return Objects.equals(customerId, customer.customerId);
        }

        @Override
        public int hashCode() {
            return customerId != null ? customerId.hashCode() : 0;
        }
    }
}