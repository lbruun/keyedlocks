# Keyed Locks
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/net.lbruun/keyedlocks/badge.svg)](https://maven-badges.herokuapp.com/maven-central/net.lbruun/keyedlocks)
[![javadoc](https://javadoc.io/badge2/net.lbruun/keyedlocks/javadoc.svg)](https://javadoc.io/doc/net.lbruun/keyedlocks) 

Utility for key-based locking.

Key-based locking means that only keys of the same value will compete for the same lock. This allows
for fine-grained locking mechanism and which can improve the throughput of an application.

This implementation is based on `ReentrantLock` and is therefore Virtual Threads friendly.


## Use case

Suppose we want processing of something to be serialized (one-by-one processing) based on object value
equality. An attempt may look like this:

```java
public void processCustomer(Customer customer) {
    syncronized (customer) {
        // do the operation 
        
    }
}
```

There are two problems with the above approach:

* It is based on the `syncronized` keyword which means it is not Virtual Threads friendly.
* The locking is based on object reference equality rather than based on object value equality.

This is what `KeyedLocks` aims to solve.


## Usage

Library is available on Maven Central:

```xml
<dependency>
    <groupId>net.lbruun</groupId>
    <artifactId>keyedlocks</artifactId>
    <version>  --LATEST--  </version>
</dependency>
```

Then use like this:

```java
KeyedLocks<Customer> customerLocks = KeyedLocks.create(Customer.class, true);

try (LockToken lockToken = customerLocks.lock(someAnimal)) {
   // Perform action guarded by the lock

}  // lock is auto-released
```

There are more options. See the [JavaDoc](https://javadoc.io/doc/net.lbruun/keyedlocks).


## Documentation

For in-depth documentation see [JavaDoc](https://javadoc.io/doc/net.lbruun/keyedlocks).

