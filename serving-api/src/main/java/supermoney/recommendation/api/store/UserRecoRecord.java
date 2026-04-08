package supermoney.recommendation.api.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import supermoney.recommendation.common.model.FatigueData;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.ScoredCandidate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deserialized form of one line from the Flink-produced recommendations.json.
 * Fatigue data is stored alongside batch-computed candidates and updated on each API impression.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserRecoRecord {

    private String userId;
    private HealthTier healthTier;
    private String computedAt;
    private List<ScoredCandidate> candidates;

    /** Per-product impression tracking. Keyed by Product.name(). Not in Flink output initially. */
    private Map<String, FatigueData> fatigue = new HashMap<>();

    public UserRecoRecord() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public HealthTier getHealthTier() { return healthTier; }
    public void setHealthTier(HealthTier healthTier) { this.healthTier = healthTier; }

    public String getComputedAt() { return computedAt; }
    public void setComputedAt(String computedAt) { this.computedAt = computedAt; }

    public List<ScoredCandidate> getCandidates() { return candidates; }
    public void setCandidates(List<ScoredCandidate> candidates) { this.candidates = candidates; }

    public Map<String, FatigueData> getFatigue() { return fatigue; }
    public void setFatigue(Map<String, FatigueData> fatigue) { this.fatigue = fatigue != null ? fatigue : new HashMap<>(); }

    /** Returns fatigue data for a specific product, or null if never shown. */
    public FatigueData getFatigueFor(Product product) {
        return fatigue.get(product.name());
    }
}
