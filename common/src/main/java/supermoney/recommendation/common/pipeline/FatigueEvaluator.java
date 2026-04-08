package supermoney.recommendation.common.pipeline;

import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.FatigueData;
import supermoney.recommendation.common.model.Product;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes frequency-capped fatigue penalty per product based on impression history.
 *
 * Penalty table:
 *   shown_count = 0           →  0  (never shown)
 *   shown_count = 1           → 10  (shown once, mild)
 *   shown_count = 2           → 25  (shown twice, increasing)
 *   shown_count >= max_cap    → 70  (fully fatigued)
 *   converted                 → 999 (permanent exclusion)
 *
 * Recency surcharge:
 *   shown_at within last 24h  → +20 additional (shown today, back off)
 *
 * max_cap comes from scoring_rules.yaml → fatigue.max_impressions per product.
 */
public class FatigueEvaluator {

    private final ScoringConfig config;

    public FatigueEvaluator(ScoringConfig config) {
        this.config = config;
    }

    /**
     * Evaluates fatigue penalty for each eligible product.
     *
     * @param fatigueData      per-product impression history (may be empty/null for new users)
     * @param eligibleProducts products to evaluate
     * @return map of Product → fatigue penalty (0.0 if no fatigue data)
     */
    public Map<Product, Double> evaluate(Map<Product, FatigueData> fatigueData,
                                          List<Product> eligibleProducts) {
        Map<Product, Double> penalties = new HashMap<>();
        for (Product product : eligibleProducts) {
            FatigueData data = fatigueData != null ? fatigueData.get(product) : null;
            penalties.put(product, computePenalty(product, data));
        }
        return penalties;
    }

    private double computePenalty(Product product, FatigueData data) {
        if (data == null) return 0.0;

        if (data.isConverted()) return 999.0;

        int shownCount = data.getShownCount();
        int maxCap     = getMaxImpressions(product);

        double basePenalty;
        if      (shownCount == 0)      basePenalty = 0.0;
        else if (shownCount == 1)      basePenalty = 10.0;
        else if (shownCount == 2)      basePenalty = 25.0;
        else if (shownCount >= maxCap) basePenalty = 70.0;
        else {
            double ratio = (double)(shownCount - 2) / Math.max(maxCap - 2, 1);
            basePenalty = 25.0 + ratio * 45.0;
        }

        double recencySurcharge = wasShownRecently(data) ? 20.0 : 0.0;
        return basePenalty + recencySurcharge;
    }

    private boolean wasShownRecently(FatigueData data) {
        String shownAt = data.getShownAt();
        if (shownAt == null || shownAt.isBlank()) return false;
        try {
            Instant lastShown = Instant.parse(shownAt);
            return lastShown.isAfter(Instant.now().minus(24, ChronoUnit.HOURS));
        } catch (Exception e) {
            return false;
        }
    }

    private int getMaxImpressions(Product product) {
        var pc = config.getProduct(product.name());
        if (pc == null || pc.getFatigue() == null) return 3;
        return Math.max(pc.getFatigue().getMaxImpressions(), 1);
    }
}
