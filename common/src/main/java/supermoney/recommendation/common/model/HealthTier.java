package supermoney.recommendation.common.model;

public enum HealthTier {
    HEALTHY,   // health_score >= 70
    NEUTRAL,   // health_score >= 40 and < 70
    STRESSED   // health_score < 40
}
