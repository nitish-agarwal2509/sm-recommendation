package supermoney.recommendation.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One product candidate after full pipeline scoring.
 * Stored in the recommendation store (top-5 per user, pre-surface-affinity).
 * Surface affinity is applied at serve time by the API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScoredCandidate {

    private Product product;
    private double propensityScore;   // 0–100, after health adjustment
    private double preSurfaceScore;   // final score before surface affinity multiplier
    private HealthTier healthTier;
    private List<String> reasonTokens; // e.g. ["income_signal", "clean_history"]

    public ScoredCandidate() {}

    public ScoredCandidate(Product product, double propensityScore, double preSurfaceScore,
                           HealthTier healthTier, List<String> reasonTokens) {
        this.product = product;
        this.propensityScore = propensityScore;
        this.preSurfaceScore = preSurfaceScore;
        this.healthTier = healthTier;
        this.reasonTokens = reasonTokens;
    }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public double getPropensityScore() { return propensityScore; }
    public void setPropensityScore(double propensityScore) { this.propensityScore = propensityScore; }

    public double getPreSurfaceScore() { return preSurfaceScore; }
    public void setPreSurfaceScore(double preSurfaceScore) { this.preSurfaceScore = preSurfaceScore; }

    public HealthTier getHealthTier() { return healthTier; }
    public void setHealthTier(HealthTier healthTier) { this.healthTier = healthTier; }

    public List<String> getReasonTokens() { return reasonTokens; }
    public void setReasonTokens(List<String> reasonTokens) { this.reasonTokens = reasonTokens; }

    @Override
    public String toString() {
        return "ScoredCandidate{product=" + product + ", preSurfaceScore=" + preSurfaceScore + "}";
    }
}
