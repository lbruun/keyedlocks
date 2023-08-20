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

import org.junit.jupiter.api.*;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyedLocksSimpleTest {

    @Test
    @DisplayName("Simple test of lock() method")
    void lockSimple() throws InterruptedException {
        String key = "FOO";
        final KeyedLocks<String> stringLocks = KeyedLocks.create(String.class);


        // Try-with-resources style
        try (KeyedLocks.LockToken<String> lockWrapper = stringLocks.lock(key)) {
            assertTrue(stringLocks.containsKey(key));
        }
        assertFalse(stringLocks.containsKey(key));


        // Classic style
        KeyedLocks.LockToken<String> lockWrapper = stringLocks.lock(key);
        try {
            assertTrue(stringLocks.containsKey(key));
        } finally {
            lockWrapper.unlock();
        }
        assertFalse(stringLocks.containsKey(key));
    }

    @Test
    @DisplayName("Simple test of tryLock() method")
    void tryLockSimple() throws InterruptedException {
        String key = "FOO";
        final KeyedLocks<String> stringLocks = KeyedLocks.create(String.class);

        Optional<KeyedLocks.LockToken<String>> lockTokenOpt1 = stringLocks.tryLock(key, 10, TimeUnit.SECONDS);
        if (lockTokenOpt1.isPresent()) {
            try (KeyedLocks.LockToken<String> lockWrapper = lockTokenOpt1.get()) {
                assertTrue(stringLocks.containsKey(key));
            }
            assertFalse(stringLocks.containsKey(key));
        } else {
            throw new RuntimeException("Unexpected");
        }


        Optional<KeyedLocks.LockToken<String>> lockToken2 = stringLocks.tryLock(key, 10, TimeUnit.SECONDS);
        if (lockToken2.isPresent()) {
            try {
                assertTrue(stringLocks.containsKey(key));
            } finally {
                lockToken2.get().unlock();
            }
            assertFalse(stringLocks.containsKey(key));
        } else {
            throw new RuntimeException("Unexpected");
        }
    }
}