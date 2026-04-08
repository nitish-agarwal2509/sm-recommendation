package supermoney.recommendation.processor.flink.model;

import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.ScoredCandidate;

import java.io.Serializable;
import java.util.List;

/**
 * Output of the recommendation pipeline for one user — passed between
 * Flink operators and serialized to JSON by JsonOutputFormatter.
 */
public class RecoResult implements Serializable {

    private String userId;
    private HealthTier healthTier;
    private String computedAt;
    private List<ScoredCandidate> candidates;

    public RecoResult() {}

    public RecoResult(String userId, HealthTier healthTier, String computedAt,
                      List<ScoredCandidate> candidates) {
        this.userId = userId;
        this.healthTier = healthTier;
        this.computedAt = computedAt;
        this.candidates = candidates;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public HealthTier getHealthTier() { return healthTier; }
    public void setHealthTier(HealthTier healthTier) { this.healthTier = healthTier; }

    public String getComputedAt() { return computedAt; }
    public void setComputedAt(String computedAt) { this.computedAt = computedAt; }

    public List<ScoredCandidate> getCandidates() { return candidates; }
    public void setCandidates(List<ScoredCandidate> candidates) { this.candidates = candidates; }
}
