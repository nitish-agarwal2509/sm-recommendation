package supermoney.recommendation.processor.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.config.ConfigLoader;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.FatigueData;
import supermoney.recommendation.common.model.Product;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FatigueEvaluatorTest {

    private FatigueEvaluator evaluator;

    @BeforeEach
    void setUp() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        evaluator = new FatigueEvaluator(config);
    }

    // ── Never shown → no penalty ──────────────────────────────────────────────

    @Test
    void neverShown_zeroPenalty() {
        FatigueData data = new FatigueData(0, null, false);
        Map<Product, Double> penalties = eval(Product.PERSONAL_LOAN, data);
        assertEquals(0.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
    }

    @Test
    void noFatigueData_zeroPenalty() {
        Map<Product, Double> penalties = evaluator.evaluate(
            Collections.emptyMap(), List.of(Product.PERSONAL_LOAN));
        assertEquals(0.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
    }

    @Test
    void nullFatigueMap_zeroPenalty() {
        Map<Product, Double> penalties = evaluator.evaluate(null, List.of(Product.PERSONAL_LOAN));
        assertEquals(0.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
    }

    // ── Penalty scaling with shown_count ──────────────────────────────────────

    @Test
    void shownOnce_penalty10() {
        FatigueData data = new FatigueData(1, oldTimestamp(), false);
        Map<Product, Double> penalties = eval(Product.PERSONAL_LOAN, data);
        assertEquals(10.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
    }

    @Test
    void shownTwice_penalty25() {
        FatigueData data = new FatigueData(2, oldTimestamp(), false);
        Map<Product, Double> penalties = eval(Product.PERSONAL_LOAN, data);
        assertEquals(25.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
    }

    @Test
    void shownAtMaxCap_penalty70() {
        // PERSONAL_LOAN max_impressions = 3 in config
        FatigueData data = new FatigueData(3, oldTimestamp(), false);
        Map<Product, Double> penalties = eval(Product.PERSONAL_LOAN, data);
        assertEquals(70.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
    }

    @Test
    void shownBeyondMaxCap_penalty70() {
        FatigueData data = new FatigueData(10, oldTimestamp(), false);
        Map<Product, Double> penalties = eval(Product.PERSONAL_LOAN, data);
        assertEquals(70.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
    }

    // ── Different products have different max_impressions ─────────────────────

    @Test
    void recharges_maxImpressions6_onlyFatiguedAt6() {
        // Recharges max_impressions = 6
        FatigueData count5 = new FatigueData(5, oldTimestamp(), false);
        FatigueData count6 = new FatigueData(6, oldTimestamp(), false);

        double penaltyAt5 = eval(Product.RECHARGES, count5).get(Product.RECHARGES);
        double penaltyAt6 = eval(Product.RECHARGES, count6).get(Product.RECHARGES);

        assertTrue(penaltyAt5 < 70.0, "Not yet at max cap at count=5 for Recharges");
        assertEquals(70.0, penaltyAt6, 0.001, "At max cap at count=6 for Recharges");
    }

    @Test
    void upiActivation_maxImpressions5_notFatiguedAt3() {
        FatigueData data = new FatigueData(3, oldTimestamp(), false);
        Map<Product, Double> penalties = eval(Product.UPI_ACTIVATION, data);
        assertTrue(penalties.get(Product.UPI_ACTIVATION) < 70.0,
            "UPI Activation at count=3 should not be fully fatigued (max is 5)");
    }

    // ── Recency surcharge: shown within 24h → +20 ────────────────────────────

    @Test
    void shownLessThan24hAgo_addsSurcharge() {
        String recentTimestamp = Instant.now().minus(2, ChronoUnit.HOURS).toString();
        FatigueData data = new FatigueData(1, recentTimestamp, false);
        Map<Product, Double> penalties = eval(Product.PERSONAL_LOAN, data);

        // Base penalty for count=1 is 10, + 20 recency surcharge = 30
        assertEquals(30.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
    }

    @Test
    void shownMoreThan24hAgo_noSurcharge() {
        String oldTs = Instant.now().minus(30, ChronoUnit.HOURS).toString();
        FatigueData data = new FatigueData(1, oldTs, false);
        Map<Product, Double> penalties = eval(Product.PERSONAL_LOAN, data);

        // Base penalty only, no surcharge
        assertEquals(10.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
    }

    @Test
    void shownExactly23hAgo_surchargeApplies() {
        String ts = Instant.now().minus(23, ChronoUnit.HOURS).toString();
        FatigueData data = new FatigueData(1, ts, false);
        Map<Product, Double> penalties = eval(Product.PERSONAL_LOAN, data);

        // Still within 24h → surcharge applies
        assertEquals(30.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
    }

    // ── Converted: permanent exclusion (penalty = 999) ───────────────────────

    @Test
    void convertedUser_penalty999() {
        FatigueData data = new FatigueData(1, oldTimestamp(), true); // converted = true
        Map<Product, Double> penalties = eval(Product.PERSONAL_LOAN, data);
        assertEquals(999.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
    }

    @Test
    void convertedUser_showCountIrrelevant() {
        FatigueData data = new FatigueData(0, null, true); // converted with 0 shows
        Map<Product, Double> penalties = eval(Product.FIXED_DEPOSIT, data);
        assertEquals(999.0, penalties.get(Product.FIXED_DEPOSIT), 0.001);
    }

    // ── Multiple products evaluated independently ─────────────────────────────

    @Test
    void multipleProducts_independentPenalties() {
        Map<Product, FatigueData> fatigueData = new HashMap<>();
        fatigueData.put(Product.PERSONAL_LOAN, new FatigueData(2, oldTimestamp(), false));    // penalty = 25
        fatigueData.put(Product.FIXED_DEPOSIT, new FatigueData(0, null, false));             // penalty = 0
        fatigueData.put(Product.UNSECURED_CARD, new FatigueData(1, oldTimestamp(), true));   // converted = 999

        Map<Product, Double> penalties = evaluator.evaluate(fatigueData,
            List.of(Product.PERSONAL_LOAN, Product.FIXED_DEPOSIT, Product.UNSECURED_CARD));

        assertEquals(25.0, penalties.get(Product.PERSONAL_LOAN), 0.001);
        assertEquals(0.0, penalties.get(Product.FIXED_DEPOSIT), 0.001);
        assertEquals(999.0, penalties.get(Product.UNSECURED_CARD), 0.001);
    }

    @Test
    void productNotInFatigueMap_zeroPenalty() {
        Map<Product, Double> penalties = evaluator.evaluate(
            Collections.emptyMap(), List.of(Product.REFERRALS));
        assertEquals(0.0, penalties.get(Product.REFERRALS), 0.001);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<Product, Double> eval(Product product, FatigueData data) {
        Map<Product, FatigueData> fatigueMap = new HashMap<>();
        fatigueMap.put(product, data);
        return evaluator.evaluate(fatigueMap, List.of(product));
    }

    /** A timestamp old enough to not trigger the recency surcharge (> 24h ago). */
    private String oldTimestamp() {
        return Instant.now().minus(48, ChronoUnit.HOURS).toString();
    }
}
