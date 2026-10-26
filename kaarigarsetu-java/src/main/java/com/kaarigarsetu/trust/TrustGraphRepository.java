package com.kaarigarsetu.trust;

/** Persistence boundary for trust graph changes. */
@FunctionalInterface
public interface TrustGraphRepository {
    void save(Edge edge);
}
