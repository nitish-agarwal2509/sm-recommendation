package supermoney.recommendation.processor.pipeline;

import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Stage 5: Health Adjuster.
 * Applies per-product health adjustment to raw propensity scores.
 * Products marked BLOCKED for the given health tier are removed entirely.
 *
 * Adjustments come from scoring_rules.yaml → health_adjustments per product.
 */
public class HealthAdjuster {

    private final ScoringConfig config;

    public HealthAdjuster(ScoringConfig config) {
        this.config = config;
    }

    /**
     * Applies health tier adjustment to each product's propensity score.
     * Products with BLOCKED adjustment for this tier are excluded from the result map.
     *
     * @param propensityScores raw propensity scores per product
     * @param tier             the user's health tier
     * @return adjusted scores (products with null adjustment removed)
     */
    public Map<Product, Double> adjust(Map<Product, Double> propensityScores, HealthTier tier) {
        Map<Product, Double> adjusted = new HashMap<>();
        String tierName = tier != null ? tier.name() : HealthTier.HEALTHY.name();

        for (Map.Entry<Product, Double> entry : propensityScores.entrySet()) {
            Product product = entry.getKey();
            double score    = entry.getValue();

            var pc = config.getProduct(product.name());
            if (pc == null) {
                adjusted.put(product, score);
                continue;
            }

            Double delta = pc.getHealthAdjustment(tierName);
            if (delta == null) {
                // BLOCKED — exclude this product
                continue;
            }
            adjusted.put(product, score + delta);
        }

        return adjusted;
    }
}
