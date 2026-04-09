package supermoney.recommendation.api.service;

import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.ScoredCandidate;
import supermoney.recommendation.common.model.Surface;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies surface affinity multipliers to pre-surface scores and returns the top-1 candidate.
 *
 * Formula (per candidate):
 *   surface_adjusted_score = (pre_surface_score - fatigue_penalty × 0.15) × affinity[surface][product]
 *
 * This re-applies fatigue on top of the batch-computed pre_surface_score to reflect
 * impressions that occurred since the last Flink run.
 */
public class SurfaceAffinityApplier {

    private final ScoringConfig scoringConfig;

    public SurfaceAffinityApplier(ScoringConfig scoringConfig) {
        this.scoringConfig = scoringConfig;
    }

    /**
     * Ranks candidates by surface-adjusted score and returns the highest-scoring one.
     *
     * Arbitration rule: UPI_ACTIVATION always wins if present and not converted (penalty < 999).
     * This mirrors the batch pipeline's "always rank 1 if eligible" rule and ensures UPI
     * dormant users are never displaced by other products at serve time.
     *
     * @param candidates       pre-surface candidates from the batch store
     * @param surface          the surface being served
     * @param fatiguePenalties per-product fatigue penalty computed at serve time
     * @return the top-1 candidate for this surface, or empty if no candidates
     */
    public Optional<RankedCandidate> applyAndRank(List<ScoredCandidate> candidates,
                                                   Surface surface,
                                                   Map<Product, Double> fatiguePenalties) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();

        // Arbitration: UPI_ACTIVATION always wins if eligible (not converted)
        for (ScoredCandidate c : candidates) {
            if (c.getProduct() == Product.UPI_ACTIVATION) {
                double upiPenalty = fatiguePenalties.getOrDefault(Product.UPI_ACTIVATION, 0.0);
                if (upiPenalty < 999.0) {
                    double affinity = scoringConfig.getSurfaceAffinityMultiplier(
                            surface.name(), c.getProduct().name());
                    double surfaceScore = (c.getPreSurfaceScore() - upiPenalty * 0.15) * affinity;
                    return Optional.of(new RankedCandidate(c, surfaceScore));
                }
                break; // UPI present but converted → fall through to normal ranking
            }
        }

        return candidates.stream()
                .map(c -> {
                    double fatiguePenalty = fatiguePenalties.getOrDefault(c.getProduct(), 0.0);
                    double affinity = scoringConfig.getSurfaceAffinityMultiplier(
                            surface.name(), c.getProduct().name());
                    double surfaceScore = (c.getPreSurfaceScore() - fatiguePenalty * 0.15) * affinity;
                    return new RankedCandidate(c, surfaceScore);
                })
                .max(Comparator.comparingDouble(RankedCandidate::surfaceScore));
    }

    /** Pairs a candidate with its surface-adjusted score. */
    public record RankedCandidate(ScoredCandidate candidate, double surfaceScore) {}
}
