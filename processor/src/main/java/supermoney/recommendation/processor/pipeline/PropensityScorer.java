package supermoney.recommendation.processor.pipeline;

import supermoney.recommendation.common.config.ProductConfig;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.config.SignalConfig;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.UserFeatures;
import supermoney.recommendation.processor.util.FeatureResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 4: Propensity Scorer.
 * Computes a 0–100 propensity score per eligible product using the
 * weighted-normalized-signal formula from scoring_rules.yaml.
 *
 * Formula:
 *   propensity = Σ(normalize(signal_i) × weight_i) / Σ(weight_i) × 100
 *
 * Also builds the reason token list (top signals that contributed most).
 */
public class PropensityScorer {

    private final ScoringConfig config;

    public PropensityScorer(ScoringConfig config) {
        this.config = config;
    }

    /**
     * Scores all eligible products and returns their raw propensity scores + reason tokens.
     *
     * @return Map of Product → [propensity_score, reason_tokens] — see PropensityResult
     */
    public Map<Product, PropensityResult> score(UserFeatures features, List<Product> eligibleProducts) {
        Map<Product, PropensityResult> results = new HashMap<>();

        for (Product product : eligibleProducts) {
            ProductConfig pc = config.getProduct(product.name());
            if (pc == null || pc.getSignals() == null) continue;

            double weightedSum = 0.0;
            double totalWeight = 0.0;
            List<String> reasonTokens = new ArrayList<>();

            for (SignalConfig signal : pc.getSignals()) {
                Double rawValue = FeatureResolver.resolve(signal.getColumn(), features);
                if (rawValue == null) continue; // skip unavailable signal

                double normalized = normalizeSignal(rawValue, signal);
                double contribution = normalized * signal.getWeight();
                weightedSum += contribution;
                totalWeight += signal.getWeight();

                // A signal contributes to reason tokens if it scores >= 60% of its max possible contribution
                if (contribution >= 0.6 * signal.getWeight()) {
                    reasonTokens.add(signal.getName());
                }
            }

            if (totalWeight == 0) continue;

            double propensity = (weightedSum / totalWeight) * 100.0;

            // Personal Loan cross-sell boost: CC bill due → add +15 if cc_bill_m0 > 0 AND days_since >= 23
            if (product == Product.PERSONAL_LOAN) {
                propensity += ccBillCrossSellBoost(features);
            }

            propensity = Math.min(propensity, 100.0);
            results.put(product, new PropensityResult(propensity, reasonTokens));
        }

        return results;
    }

    private double normalizeSignal(double raw, SignalConfig signal) {
        String type = signal.getType();
        return switch (type) {
            case "binary"   -> Math.min(1.0, Math.max(0.0, raw)); // already 0.0 or 1.0 (or 0.5)
            case "inverted" -> 1.0 - normalize(raw, signal.getMin(), signal.getMax());
            default         -> normalize(raw, signal.getMin(), signal.getMax()); // "numeric"
        };
    }

    /** clamp((x - min) / (max - min), 0, 1) */
    private double normalize(double x, Double min, Double max) {
        if (min == null || max == null || max.equals(min)) return 0.0;
        return Math.max(0.0, Math.min(1.0, (x - min) / (max - min)));
    }

    /** Personal Loan cross-sell boost: CC bill due >= 23 days → +15 propensity */
    private double ccBillCrossSellBoost(UserFeatures f) {
        if (f.getCcBillM0() == null || f.getCcBillM0() <= 0) return 0.0;
        if (f.getCcBillLatestDate() == null) return 0.0;
        Double daysSince = FeatureResolver.resolve("ccBillLatestDate", f);
        if (daysSince != null && daysSince >= 23) return 15.0;
        return 0.0;
    }

    /** Immutable result of propensity scoring for one product. */
    public static class PropensityResult {
        private final double score;
        private final List<String> reasonTokens;

        public PropensityResult(double score, List<String> reasonTokens) {
            this.score = score;
            this.reasonTokens = reasonTokens;
        }

        public double getScore() { return score; }
        public List<String> getReasonTokens() { return reasonTokens; }
    }
}
