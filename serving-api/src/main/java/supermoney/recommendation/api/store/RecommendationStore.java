package supermoney.recommendation.api.store;

import supermoney.recommendation.common.model.Product;

import java.time.Instant;
import java.util.Optional;

/**
 * Read/write interface for the recommendation store.
 * V0: backed by a local JSON file. Production: Google Cloud Bigtable.
 */
public interface RecommendationStore {

    /**
     * Returns the batch-computed record for the given user, including any fatigue data
     * accumulated since the last Flink run.
     */
    Optional<UserRecoRecord> findByUserId(String userId);

    /**
     * Atomically increments the impression count for the given product and records the timestamp.
     * Called asynchronously after every successful GET /recommendation response.
     * The increment is computed inside the synchronized store to prevent read-increment-write races.
     */
    void incrementFatigue(String userId, Product product, Instant shownAt);
}
