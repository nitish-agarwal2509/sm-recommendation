package supermoney.recommendation.processor.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.config.ConfigLoader;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.ScoredCandidate;
import supermoney.recommendation.processor.pipeline.PropensityScorer.PropensityResult;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RankerTest {

    private Ranker ranker;

    @BeforeEach
    void setUp() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        ranker = new Ranker(config);
    }

    @Test
    void upiActivationAlwaysRanksFirst() {
        Map<Product, PropensityResult> scores = new HashMap<>();
        scores.put(Product.UPI_ACTIVATION, result(30.0));   // low propensity
        scores.put(Product.PERSONAL_LOAN,  result(90.0));   // high propensity
        scores.put(Product.FIXED_DEPOSIT,  result(70.0));

        List<ScoredCandidate> ranked = ranker.rank(scores, noBoosts(), noFatigue(), HealthTier.HEALTHY);

        assertFalse(ranked.isEmpty());
        assertEquals(Product.UPI_ACTIVATION, ranked.get(0).getProduct(),
            "UPI_ACTIVATION must always be rank #1 when eligible");
    }

    @Test
    void maxOneCreditProductInTop5() {
        Map<Product, PropensityResult> scores = new HashMap<>();
        scores.put(Product.PERSONAL_LOAN,  result(90.0));
        scores.put(Product.UNSECURED_CARD, result(85.0));
        scores.put(Product.SECURED_CARD,   result(80.0));
        scores.put(Product.FIXED_DEPOSIT,  result(70.0));
        scores.put(Product.BILL_PAYMENTS,  result(60.0));
        scores.put(Product.REFERRALS,      result(50.0));

        List<ScoredCandidate> ranked = ranker.rank(scores, noBoosts(), noFatigue(), HealthTier.HEALTHY);

        long creditCount = ranked.stream()
            .filter(c -> isCreditProduct(c.getProduct()))
            .count();
        assertTrue(creditCount <= 1, "Expected at most 1 credit product, got: " + creditCount);
    }

    @Test
    void fdPreferredOverFlightsWhenScoresClose() {
        Map<Product, PropensityResult> scores = new HashMap<>();
        scores.put(Product.FIXED_DEPOSIT, result(50.0));
        scores.put(Product.FLIGHTS,       result(56.0));  // Flights ahead by small margin in final_score
        scores.put(Product.REFERRALS,     result(30.0));

        List<ScoredCandidate> ranked = ranker.rank(scores, noBoosts(), noFatigue(), HealthTier.HEALTHY);

        int fdIdx      = indexOf(ranked, Product.FIXED_DEPOSIT);
        int flightsIdx = indexOf(ranked, Product.FLIGHTS);
        assertTrue(fdIdx < flightsIdx,
            "FD should rank before FLIGHTS when scores within 5 pts. FD idx: " + fdIdx + ", Flights idx: " + flightsIdx);
    }

    @Test
    void flightsBeatsFdWhenClearlyAhead() {
        Map<Product, PropensityResult> scores = new HashMap<>();
        scores.put(Product.FIXED_DEPOSIT, result(40.0));
        scores.put(Product.FLIGHTS,       result(80.0));  // Flights clearly ahead (final_score diff > 5)

        List<ScoredCandidate> ranked = ranker.rank(scores, noBoosts(), noFatigue(), HealthTier.HEALTHY);

        int fdIdx      = indexOf(ranked, Product.FIXED_DEPOSIT);
        int flightsIdx = indexOf(ranked, Product.FLIGHTS);
        assertTrue(flightsIdx < fdIdx,
            "FLIGHTS should rank before FD when clearly ahead. FD idx: " + fdIdx + ", Flights idx: " + flightsIdx);
    }

    @Test
    void convertedProductExcludedFromResults() {
        Map<Product, PropensityResult> scores = new HashMap<>();
        scores.put(Product.PERSONAL_LOAN, result(90.0));
        scores.put(Product.FIXED_DEPOSIT, result(70.0));

        Map<Product, Double> fatigue = new HashMap<>();
        fatigue.put(Product.PERSONAL_LOAN, 999.0);  // converted

        List<ScoredCandidate> ranked = ranker.rank(scores, noBoosts(), fatigue, HealthTier.HEALTHY);

        boolean hasLoan = ranked.stream().anyMatch(c -> c.getProduct() == Product.PERSONAL_LOAN);
        assertFalse(hasLoan, "Converted product must not appear in results");
    }

    @Test
    void resultsNeverExceedTop5() {
        Map<Product, PropensityResult> scores = new HashMap<>();
        for (Product p : Product.values()) {
            scores.put(p, result(50.0));
        }

        List<ScoredCandidate> ranked = ranker.rank(scores, noBoosts(), noFatigue(), HealthTier.HEALTHY);

        assertTrue(ranked.size() <= 5, "Should never return more than 5 candidates");
    }

    @Test
    void emptyInputReturnsEmptyList() {
        List<ScoredCandidate> ranked = ranker.rank(Collections.emptyMap(), noBoosts(), noFatigue(), HealthTier.HEALTHY);
        assertTrue(ranked.isEmpty());
    }

    @Test
    void fatiguePenaltyReducesScore() {
        Map<Product, PropensityResult> scores = new HashMap<>();
        scores.put(Product.PERSONAL_LOAN, result(70.0));
        scores.put(Product.FIXED_DEPOSIT, result(65.0));

        // Loan without fatigue
        List<ScoredCandidate> noFatigueResult = ranker.rank(scores, noBoosts(), noFatigue(), HealthTier.HEALTHY);
        double loanScoreNoFatigue = noFatigueResult.stream()
            .filter(c -> c.getProduct() == Product.PERSONAL_LOAN)
            .mapToDouble(ScoredCandidate::getPreSurfaceScore).findFirst().orElse(0.0);

        // Loan with high fatigue
        Map<Product, Double> fatigue = new HashMap<>();
        fatigue.put(Product.PERSONAL_LOAN, 70.0);  // max fatigue
        List<ScoredCandidate> fatiguedResult = ranker.rank(scores, noBoosts(), fatigue, HealthTier.HEALTHY);
        double loanScoreWithFatigue = fatiguedResult.stream()
            .filter(c -> c.getProduct() == Product.PERSONAL_LOAN)
            .mapToDouble(ScoredCandidate::getPreSurfaceScore).findFirst().orElse(0.0);

        assertTrue(loanScoreWithFatigue < loanScoreNoFatigue,
            "Fatigue penalty should reduce score");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private PropensityResult result(double score) {
        return new PropensityResult(score, List.of());
    }

    private Map<Product, Double> noBoosts() {
        Map<Product, Double> boosts = new HashMap<>();
        for (Product p : Product.values()) boosts.put(p, 0.0);
        return boosts;
    }

    private Map<Product, Double> noFatigue() {
        Map<Product, Double> fatigue = new HashMap<>();
        for (Product p : Product.values()) fatigue.put(p, 0.0);
        return fatigue;
    }

    private boolean isCreditProduct(Product p) {
        return p == Product.PERSONAL_LOAN || p == Product.UNSECURED_CARD
            || p == Product.SECURED_CARD || p == Product.CC_BILL_PAYMENT;
    }

    private int indexOf(List<ScoredCandidate> ranked, Product product) {
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).getProduct() == product) return i;
        }
        return -1;
    }
}
