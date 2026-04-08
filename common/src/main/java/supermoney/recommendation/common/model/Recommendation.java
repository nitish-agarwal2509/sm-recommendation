package supermoney.recommendation.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The API response object — one recommendation per surface call.
 * Surface affinity has already been applied; this is the final output.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Recommendation {

    private String userId;
    private Surface surface;
    private Product product;
    private double finalScore;
    private HealthTier healthTier;
    private List<String> reasonTokens;
    private String creativeVariant;
    private String computedAt; // batch run timestamp from Flink

    public Recommendation() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Surface getSurface() { return surface; }
    public void setSurface(Surface surface) { this.surface = surface; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public double getFinalScore() { return finalScore; }
    public void setFinalScore(double finalScore) { this.finalScore = finalScore; }

    public HealthTier getHealthTier() { return healthTier; }
    public void setHealthTier(HealthTier healthTier) { this.healthTier = healthTier; }

    public List<String> getReasonTokens() { return reasonTokens; }
    public void setReasonTokens(List<String> reasonTokens) { this.reasonTokens = reasonTokens; }

    public String getCreativeVariant() { return creativeVariant; }
    public void setCreativeVariant(String creativeVariant) { this.creativeVariant = creativeVariant; }

    public String getComputedAt() { return computedAt; }
    public void setComputedAt(String computedAt) { this.computedAt = computedAt; }
}
