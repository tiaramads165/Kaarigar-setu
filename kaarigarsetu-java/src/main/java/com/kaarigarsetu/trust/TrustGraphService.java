package com.kaarigarsetu.trust;

import com.kaarigarsetu.exception.LowTrustScoreException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory adjacency list used by the service layer before graph persistence/ML integration. */
public class TrustGraphService {
    private final Map<String, List<Edge>> adjacencyList = new HashMap<>();
    private final TrustGraphRepository repository;

    public TrustGraphService() {
        this(edge -> { });
    }

    public TrustGraphService(TrustGraphRepository repository) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository must not be null");
    }

    public void addEdge(Edge edge) {
        repository.save(edge);
        adjacencyList.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
        adjacencyList.computeIfAbsent(edge.target(), ignored -> new ArrayList<>());
    }

    public Map<String, List<Edge>> adjacencyList() {
        Map<String, List<Edge>> copy = new HashMap<>();
        adjacencyList.forEach((node, edges) -> copy.put(node, List.copyOf(edges)));
        return Map.copyOf(copy);
    }

    public void requireMinimumTrust(BigDecimal actualScore, BigDecimal requiredScore) {
        if (actualScore == null || requiredScore == null) {
            throw new IllegalArgumentException("trust scores must not be null");
        }
        if (actualScore.compareTo(requiredScore) < 0) {
            throw new LowTrustScoreException("Trust score " + actualScore + " is below required score " + requiredScore);
        }
    }
}
