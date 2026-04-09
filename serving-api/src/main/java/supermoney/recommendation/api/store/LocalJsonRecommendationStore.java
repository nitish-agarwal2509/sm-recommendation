package supermoney.recommendation.api.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import supermoney.recommendation.api.config.ApiConfig;
import supermoney.recommendation.common.model.FatigueData;
import supermoney.recommendation.common.model.Product;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V0 local implementation: loads recommendations.json (Flink output) into memory on startup.
 *
 * Read path: O(1) ConcurrentHashMap lookup — no disk I/O per request.
 * Write path: updateFatigue() updates in-memory state, then rewrites the whole JSON file.
 *             Called asynchronously (via FatigueWriter) so file I/O never blocks request latency.
 */
@Repository
public class LocalJsonRecommendationStore implements RecommendationStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final ApiConfig apiConfig;
    private final ConcurrentHashMap<String, UserRecoRecord> cache = new ConcurrentHashMap<>();

    public LocalJsonRecommendationStore(ApiConfig apiConfig) {
        this.apiConfig = apiConfig;
    }

    @PostConstruct
    public void load() {
        Path path = Path.of(apiConfig.getStore().getLocalJsonPath());
        if (!Files.exists(path)) {
            // No output file yet (processor hasn't run) — start with empty cache
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path);
            for (String line : lines) {
                if (!line.isBlank()) {
                    UserRecoRecord record = MAPPER.readValue(line, UserRecoRecord.class);
                    cache.put(record.getUserId(), record);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load recommendations.json: " + path, e);
        }
    }

    @Override
    public Optional<UserRecoRecord> findByUserId(String userId) {
        return Optional.ofNullable(cache.get(userId));
    }

    @Override
    public synchronized void incrementFatigue(String userId, Product product, Instant shownAt) {
        UserRecoRecord record = cache.get(userId);
        if (record == null) return;

        // Compute new count inside the synchronized block to prevent read-increment-write races
        FatigueData existing = record.getFatigueFor(product);
        int newCount = existing != null ? existing.getShownCount() + 1 : 1;

        record.getFatigue().put(product.name(), new FatigueData(newCount, shownAt.toString(), false));
        persist();
    }

    /** Rewrites the full JSONL file from the current in-memory cache. */
    private void persist() {
        Path path = Path.of(apiConfig.getStore().getLocalJsonPath());
        try {
            Files.createDirectories(path.getParent());
            StringBuilder sb = new StringBuilder();
            for (UserRecoRecord record : cache.values()) {
                sb.append(MAPPER.writeValueAsString(record)).append("\n");
            }
            Files.writeString(path, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist recommendations.json", e);
        }
    }
}
