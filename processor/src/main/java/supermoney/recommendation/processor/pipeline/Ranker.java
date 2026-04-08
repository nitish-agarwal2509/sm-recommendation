package supermoney.recommendation.processor.pipeline;

import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.ScoredCandidate;
import supermoney.recommendation.processor.pipeline.PropensityScorer.PropensityResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Stage 8: Ranker + Arbitration.
 * Applies the final score formula and diversity/ethics rules to produce
 * the top-5 pre-surface scored candidates.
 *
 * Final score formula:
 *   final_score[p] = (adjusted_propensity × 0.50)
 *                  + (business_priority × 100 × 0.25)
 *                  - (fatigue_penalty × 0.15)
 *                  + (trigger_boost × 0.10)
 *
 * Arbitration rules:
 *   1. UPI_ACTIVATION → always rank #1 if eligible (overrides all scores)
 *   2. Max 1 credit product in top-5
 *   3. FIXED_DEPOSIT preferred over FLIGHTS if scores within 5 points
 *   4. Tie-break: prefer lower business_priority product (avoids always showing highest-monetizing)
 */
public class Ranker {

    private static final int TOP_N = 5;

    private static final Set<Product> CREDIT_PRODUCTS = Set.of(
        Product.PERSONAL_LOAN,
        Product.UNSECURED_CARD,
        Product.SECURED_CARD,
        Product.CC_BILL_PAYMENT
    );

    private final ScoringConfig config;

    public Ranker(ScoringConfig config) {
        this.config = config;
    }

    /**
     * Produces the ranked top-5 candidates.
     *
     * @param propensityResults  propensity scores + reason tokens per product
     * @param triggerBoosts      trigger boost per product
     * @param fatiguePenalties   fatigue penalty per product
     * @param healthTier         user's health tier (set on ScoredCandidate for downstream use)
     * @return top-5 ScoredCandidates sorted by preSurfaceScore DESC
     */
    public List<ScoredCandidate> rank(Map<Product, PropensityResult> propensityResults,
                                      Map<Product, Double> triggerBoosts,
                                      Map<Product, Double> fatiguePenalties,
                                      HealthTier healthTier) {
        if (propensityResults.isEmpty()) return Collections.emptyList();

        // ── Step 1: Compute final score for each product ──────────────────────
        Map<Product, Double> finalScores = new HashMap<>();
        for (Product product : propensityResults.keySet()) {
            double propensity = propensityResults.get(product).getScore();
            double priority   = getBusinessPriority(product);
            double fatigue    = fatiguePenalties.getOrDefault(product, 0.0);
            double boost      = triggerBoosts.getOrDefault(product, 0.0);

            double finalScore = (propensity   * 0.50)
                              + (priority * 100 * 0.25)
                              - (fatigue      * 0.15)
                              + (boost        * 0.10);

            // Products with converted-level fatigue (999) effectively score negatively — exclude
            if (fatigue >= 999.0) continue;

            finalScores.put(product, finalScore);
        }

        // ── Step 2: Sort by final score DESC, tie-break by lower business priority ─
        List<Product> sorted = finalScores.entrySet().stream()
            .sorted(Map.Entry.<Product, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparingDouble(e -> getBusinessPriority(e.getKey())))  // lower priority = preferred tie-break
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        // ── Step 3: Apply arbitration rules ───────────────────────────────────
        sorted = applyArbitration(sorted, finalScores);

        // ── Step 4: Build ScoredCandidate list (top-5) ────────────────────────
        return sorted.stream()
            .limit(TOP_N)
            .map(p -> {
                PropensityResult pr = propensityResults.get(p);
                return new ScoredCandidate(
                    p,
                    pr.getScore(),
                    finalScores.get(p),
                    healthTier,
                    pr.getReasonTokens()
                );
            })
            .collect(Collectors.toList());
    }

    private List<Product> applyArbitration(List<Product> sorted, Map<Product, Double> finalScores) {
        List<Product> result = new ArrayList<>(sorted);

        // ── Rule 1: UPI_ACTIVATION → always rank #1 if eligible ───────────────
        if (result.contains(Product.UPI_ACTIVATION)) {
            result.remove(Product.UPI_ACTIVATION);
            result.add(0, Product.UPI_ACTIVATION);
        }

        // ── Rule 2: Max 1 credit product in top-5 ─────────────────────────────
        boolean creditSeen = false;
        List<Product> filtered = new ArrayList<>();
        for (Product p : result) {
            if (CREDIT_PRODUCTS.contains(p)) {
                if (!creditSeen) {
                    filtered.add(p);
                    creditSeen = true;
                }
                // second+ credit product: skip (drop from top-5)
            } else {
                filtered.add(p);
            }
        }
        result = filtered;

        // ── Rule 3: FD preferred over FLIGHTS if scores within 5 points ───────
        int fdIdx     = result.indexOf(Product.FIXED_DEPOSIT);
        int flightsIdx = result.indexOf(Product.FLIGHTS);
        if (fdIdx > -1 && flightsIdx > -1 && flightsIdx < fdIdx) {
            // FLIGHTS currently ahead of FD — check if scores are within 5 points
            double fdScore      = finalScores.getOrDefault(Product.FIXED_DEPOSIT, 0.0);
            double flightsScore = finalScores.getOrDefault(Product.FLIGHTS, 0.0);
            if (flightsScore - fdScore <= 5.0) {
                // Swap: put FD before FLIGHTS
                result.remove(fdIdx);
                result.add(flightsIdx, Product.FIXED_DEPOSIT);
            }
        }

        return result;
    }

    private double getBusinessPriority(Product product) {
        var pc = config.getProduct(product.name());
        return pc != null ? pc.getBusinessPriority() : 0.5;
    }
}
