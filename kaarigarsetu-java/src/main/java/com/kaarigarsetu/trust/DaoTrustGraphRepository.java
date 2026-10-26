package com.kaarigarsetu.trust;

import com.kaarigarsetu.dao.TrustEdgeDao;
import com.kaarigarsetu.model.TrustEdge;

import java.util.Objects;

/** Adapts service-layer string node identifiers to the persisted worker-id edge model. */
public class DaoTrustGraphRepository implements TrustGraphRepository {
    private final TrustEdgeDao trustEdgeDao;

    public DaoTrustGraphRepository(TrustEdgeDao trustEdgeDao) {
        this.trustEdgeDao = Objects.requireNonNull(trustEdgeDao, "trustEdgeDao must not be null");
    }

    @Override
    public void save(Edge edge) {
        trustEdgeDao.insert(new TrustEdge(null, workerId(edge.source()), workerId(edge.target()), edge.weight(), true));
    }

    private long workerId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Trust graph DAO persistence requires numeric worker identifiers", exception);
        }
    }
}
