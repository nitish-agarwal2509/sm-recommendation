package supermoney.recommendation.api.fatigue;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import supermoney.recommendation.api.store.RecommendationStore;
import supermoney.recommendation.common.model.Product;

import java.time.Instant;

/**
 * Writes impression tracking data asynchronously after every successful recommendation response.
 *
 * Using @Async ensures file I/O never adds to request latency.
 * The store's updateFatigue() is synchronized to prevent concurrent file writes.
 */
@Component
public class FatigueWriter {

    private final RecommendationStore store;

    public FatigueWriter(RecommendationStore store) {
        this.store = store;
    }

    @Async
    public void recordImpression(String userId, Product product) {
        store.incrementFatigue(userId, product, Instant.now());
    }
}
