package supermoney.recommendation.api.controller.dto;

import java.util.List;

/**
 * Successful GET /recommendation response body.
 */
public class RecommendationResponse {

    private String userId;
    private String surface;
    private RecommendationDto recommendation;

    public RecommendationResponse() {}

    public RecommendationResponse(String userId, String surface, RecommendationDto recommendation) {
        this.userId = userId;
        this.surface = surface;
        this.recommendation = recommendation;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSurface() { return surface; }
    public void setSurface(String surface) { this.surface = surface; }

    public RecommendationDto getRecommendation() { return recommendation; }
    public void setRecommendation(RecommendationDto recommendation) { this.recommendation = recommendation; }

    public static class RecommendationDto {
        private String product;
        private double finalScore;
        private String healthTier;
        private List<String> reasonTokens;
        private String creativeVariant;
        private String computedAt;

        public RecommendationDto() {}

        public RecommendationDto(String product, double finalScore, String healthTier,
                                 List<String> reasonTokens, String creativeVariant, String computedAt) {
            this.product = product;
            this.finalScore = finalScore;
            this.healthTier = healthTier;
            this.reasonTokens = reasonTokens;
            this.creativeVariant = creativeVariant;
            this.computedAt = computedAt;
        }

        public String getProduct() { return product; }
        public void setProduct(String product) { this.product = product; }

        public double getFinalScore() { return finalScore; }
        public void setFinalScore(double finalScore) { this.finalScore = finalScore; }

        public String getHealthTier() { return healthTier; }
        public void setHealthTier(String healthTier) { this.healthTier = healthTier; }

        public List<String> getReasonTokens() { return reasonTokens; }
        public void setReasonTokens(List<String> reasonTokens) { this.reasonTokens = reasonTokens; }

        public String getCreativeVariant() { return creativeVariant; }
        public void setCreativeVariant(String creativeVariant) { this.creativeVariant = creativeVariant; }

        public String getComputedAt() { return computedAt; }
        public void setComputedAt(String computedAt) { this.computedAt = computedAt; }
    }
}
