package supermoney.recommendation.processor.pipeline;

import supermoney.recommendation.common.config.ConfigLoader;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.FatigueData;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.ScoredCandidate;
import supermoney.recommendation.common.model.UserFeatures;
import supermoney.recommendation.processor.pipeline.PropensityScorer.PropensityResult;

import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full 6-stage recommendation pipeline for one user.
 *
 * Stage 0: Feature Derivation (health score, surplus, DTI, UPI ticket, bill urgency)
 * Stage 1: Candidate Generation (all 11 products)
 * Stage 2: Health Gate (block credit products for STRESSED users)
 * Stage 3: Eligibility Filter (hard pass/fail per product)
 * Stage 4: Propensity Scoring (weighted normalized signals)
 * Stage 5: Health Adjustment (apply tier-based delta to propensity)
 * Stage 6: Trigger Boost (batch-approximated urgency signals)
 * Stage 7: Fatigue Penalty (frequency-capped impression history)
 * Stage 8: Ranking + Arbitration (final formula → top-5)
 *
 * Returns: top-5 ScoredCandidates sorted by preSurfaceScore DESC.
 * The Serving API applies surface affinity at read time → top-1 per surface call.
 */
public class RecommendationPipeline {

    private final FeatureDeriver featureDeriver;
    private final HealthGate healthGate;
    private final EligibilityFilter eligibilityFilter;
    private final PropensityScorer propensityScorer;
    private final HealthAdjuster healthAdjuster;
    private final TriggerBoostEvaluator triggerBoostEvaluator;
    private final FatigueEvaluator fatigueEvaluator;
    private final Ranker ranker;

    public RecommendationPipeline(ScoringConfig config) {
        this(config, new TriggerBoostEvaluator());
    }

    /** Constructor with injectable TriggerBoostEvaluator (allows fixed date for testing). */
    public RecommendationPipeline(ScoringConfig config, TriggerBoostEvaluator triggerBoostEvaluator) {
        this.featureDeriver       = new FeatureDeriver();
        this.healthGate           = new HealthGate();
        this.eligibilityFilter    = new EligibilityFilter();
        this.propensityScorer     = new PropensityScorer(config);
        this.healthAdjuster       = new HealthAdjuster(config);
        this.triggerBoostEvaluator = triggerBoostEvaluator;
        this.fatigueEvaluator     = new FatigueEvaluator(config);
        this.ranker               = new Ranker(config);
    }

    /**
     * Runs the full pipeline for one user.
     *
     * @param features    raw SMS insights features for the user (mutated in-place by FeatureDeriver)
     * @param fatigueData per-product impression history (pass empty map for new users)
     * @return top-5 scored candidates, or empty list if no eligible products
     */
    public List<ScoredCandidate> run(UserFeatures features, Map<Product, FatigueData> fatigueData) {
        // Stage 0: Derive secondary features
        featureDeriver.derive(features);

        // Stage 1: Start with all products
        List<Product> candidates = HealthGate.allProducts();

        // Stage 2: Health gate
        candidates = healthGate.filter(features, candidates);
        if (candidates.isEmpty()) return List.of();

        // Stage 3: Eligibility filter
        candidates = eligibilityFilter.filter(features, candidates);
        if (candidates.isEmpty()) return List.of();

        // Stage 4: Propensity scoring
        Map<Product, PropensityResult> propensityResults =
            propensityScorer.score(features, candidates);
        if (propensityResults.isEmpty()) return List.of();

        // Stage 5: Health adjustment (may further remove BLOCKED products)
        Map<Product, Double> adjustedPropensity =
            healthAdjuster.adjust(toScoreMap(propensityResults), features.getHealthTier());

        // Rebuild candidate list after adjustment (some may have been BLOCKED)
        List<Product> remainingCandidates = List.copyOf(adjustedPropensity.keySet());
        if (remainingCandidates.isEmpty()) return List.of();

        // Stage 6: Trigger boosts
        Map<Product, Double> triggerBoosts =
            triggerBoostEvaluator.evaluate(features, remainingCandidates);

        // Stage 7: Fatigue penalties
        Map<Product, Double> fatiguePenalties =
            fatigueEvaluator.evaluate(fatigueData, remainingCandidates);

        // Rebuild PropensityResult map with adjusted scores (for Ranker)
        Map<Product, PropensityResult> adjustedResults = new java.util.HashMap<>();
        for (Product p : remainingCandidates) {
            PropensityResult original = propensityResults.get(p);
            if (original != null) {
                double adjustedScore = adjustedPropensity.getOrDefault(p, original.getScore());
                adjustedResults.put(p, new PropensityResult(adjustedScore, original.getReasonTokens()));
            }
        }

        // Stage 8: Rank + arbitration
        return ranker.rank(adjustedResults, triggerBoosts, fatiguePenalties, features.getHealthTier());
    }

    private Map<Product, Double> toScoreMap(Map<Product, PropensityResult> results) {
        Map<Product, Double> scores = new java.util.HashMap<>();
        results.forEach((p, r) -> scores.put(p, r.getScore()));
        return scores;
    }
}
