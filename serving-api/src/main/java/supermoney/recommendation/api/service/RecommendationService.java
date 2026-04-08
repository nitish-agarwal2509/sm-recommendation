package supermoney.recommendation.api.service;

import org.springframework.stereotype.Service;
import supermoney.recommendation.api.config.ApiConfig;
import supermoney.recommendation.api.fatigue.FatigueWriter;
import supermoney.recommendation.api.store.RecommendationStore;
import supermoney.recommendation.api.store.UserRecoRecord;
import supermoney.recommendation.common.model.FatigueData;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.ScoredCandidate;
import supermoney.recommendation.common.model.Surface;
import supermoney.recommendation.common.pipeline.FatigueEvaluator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the serve-time pipeline:
 *   1. Load user record from store
 *   2. Compute fatigue penalties (using current impression data)
 *   3. Apply surface affinity + fatigue → top-1
 *   4. Trigger async fatigue write
 *   5. Return result
 */
@Service
public class RecommendationService {

    private final RecommendationStore store;
    private final FatigueWriter fatigueWriter;
    private final SurfaceAffinityApplier surfaceAffinityApplier;
    private final FatigueEvaluator fatigueEvaluator;
    private final ApiConfig apiConfig;

    public RecommendationService(RecommendationStore store,
                                 FatigueWriter fatigueWriter,
                                 SurfaceAffinityApplier surfaceAffinityApplier,
                                 FatigueEvaluator fatigueEvaluator,
                                 ApiConfig apiConfig) {
        this.store                 = store;
        this.fatigueWriter         = fatigueWriter;
        this.surfaceAffinityApplier = surfaceAffinityApplier;
        this.fatigueEvaluator      = fatigueEvaluator;
        this.apiConfig             = apiConfig;
    }

    /**
     * Returns the top-1 recommendation for the given user and surface.
     *
     * @param userId  user identifier
     * @param surface the surface being served
     * @return result containing the candidate and surface score, or empty if no record/candidates
     */
    public Optional<RecommendationResult> getRecommendation(String userId, Surface surface) {
        Optional<UserRecoRecord> recordOpt = store.findByUserId(userId);
        if (recordOpt.isEmpty()) return Optional.empty();

        UserRecoRecord record = recordOpt.get();
        if (record.getCandidates() == null || record.getCandidates().isEmpty()) {
            return Optional.empty();
        }

        // Build fatigue map from stored impression data
        Map<Product, FatigueData> fatigueData = buildFatigueMap(record);

        // Compute fatigue penalties for all candidates at serve time
        List<Product> products = record.getCandidates().stream()
                .map(ScoredCandidate::getProduct).toList();
        Map<Product, Double> fatiguePenalties = fatigueEvaluator.evaluate(fatigueData, products);

        // Apply surface affinity and pick top-1
        Optional<SurfaceAffinityApplier.RankedCandidate> top = surfaceAffinityApplier.applyAndRank(
                record.getCandidates(), surface, fatiguePenalties);

        if (top.isEmpty()) return Optional.empty();

        SurfaceAffinityApplier.RankedCandidate winner = top.get();
        Product product = winner.candidate().getProduct();

        // Write fatigue async (non-blocking)
        FatigueData existing = fatigueData.get(product);
        int newCount = existing != null ? existing.getShownCount() + 1 : 1;
        fatigueWriter.recordImpression(userId, product, newCount);

        return Optional.of(new RecommendationResult(
                winner.candidate(),
                winner.surfaceScore(),
                record.getComputedAt(),
                apiConfig.getCreativeVariant(product.name())
        ));
    }

    private Map<Product, FatigueData> buildFatigueMap(UserRecoRecord record) {
        Map<Product, FatigueData> map = new HashMap<>();
        if (record.getFatigue() != null) {
            for (Map.Entry<String, FatigueData> entry : record.getFatigue().entrySet()) {
                try {
                    Product p = Product.valueOf(entry.getKey());
                    map.put(p, entry.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Unknown product key in fatigue data — skip
                }
            }
        }
        return map;
    }

    /** Carries the result of serve-time scoring back to the controller. */
    public record RecommendationResult(
            ScoredCandidate candidate,
            double surfaceScore,
            String computedAt,
            String creativeVariant
    ) {}
}
