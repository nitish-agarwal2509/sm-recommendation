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
     * Records an impression for the given product.
     * Called asynchronously after every successful GET /recommendation response.
     */
    void updateFatigue(String userId, Product product, int shownCount, Instant shownAt);
}
