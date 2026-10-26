package com.kaarigarsetu.config;

import java.util.function.Supplier;

/** Runs a unit of work atomically. DAOs that obtain connections from the same provider join it automatically. */
@FunctionalInterface
public interface Transactions {
    <T> T inTransaction(Supplier<T> work);

    /** No transaction boundary: used by pure unit tests that run against in-memory fakes. */
    static Transactions none() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return work.get();
            }
        };
    }
}
