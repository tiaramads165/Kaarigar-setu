package com.kaarigarsetu.trust;

import com.kaarigarsetu.exception.LowTrustScoreException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrustGraphServiceTest {
    @Test
    void storesAnAdjacencyListAndEnforcesTrustThreshold() {
        List<Edge> persisted = new ArrayList<>();
        TrustGraphService service = new TrustGraphService(persisted::add);
        Edge edge = new Edge("worker-1", "worker-2", new BigDecimal("0.75"));
        service.addEdge(edge);

        assertEquals(1, service.adjacencyList().get("worker-1").size());
        assertEquals(0, service.adjacencyList().get("worker-2").size());
        assertEquals(List.of(edge), persisted);
        service.requireMinimumTrust(new BigDecimal("80"), new BigDecimal("75"));
        assertThrows(LowTrustScoreException.class,
                () -> service.requireMinimumTrust(new BigDecimal("50"), new BigDecimal("75")));
    }
}
