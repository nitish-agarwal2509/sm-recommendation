package supermoney.recommendation.processor.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.config.ConfigLoader;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthAdjusterTest {

    private HealthAdjuster adjuster;

    @BeforeEach
    void setUp() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        adjuster = new HealthAdjuster(config);
    }

    // ── HEALTHY: no adjustments ───────────────────────────────────────────────

    @Test
    void healthy_noAdjustmentForAnyProduct() {
        Map<Product, Double> scores = allProductsAt(70.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.HEALTHY);

        assertEquals(11, adjusted.size(), "All 11 products should remain for HEALTHY user");
        adjusted.forEach((p, score) ->
            assertEquals(70.0, score, 0.001, "No adjustment expected for HEALTHY, product: " + p));
    }

    // ── NEUTRAL: credit products get penalty, FD gets boost ───────────────────

    @Test
    void neutral_personalLoanGets_minus10() {
        Map<Product, Double> scores = singleProduct(Product.PERSONAL_LOAN, 60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.NEUTRAL);

        assertTrue(adjusted.containsKey(Product.PERSONAL_LOAN));
        assertEquals(50.0, adjusted.get(Product.PERSONAL_LOAN), 0.001);
    }

    @Test
    void neutral_unsecuredCardGets_minus10() {
        Map<Product, Double> scores = singleProduct(Product.UNSECURED_CARD, 60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.NEUTRAL);

        assertEquals(50.0, adjusted.get(Product.UNSECURED_CARD), 0.001);
    }

    @Test
    void neutral_securedCardGets_minus5() {
        Map<Product, Double> scores = singleProduct(Product.SECURED_CARD, 60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.NEUTRAL);

        assertEquals(55.0, adjusted.get(Product.SECURED_CARD), 0.001);
    }

    @Test
    void neutral_ccBillGets_minus5() {
        Map<Product, Double> scores = singleProduct(Product.CC_BILL_PAYMENT, 60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.NEUTRAL);

        assertEquals(55.0, adjusted.get(Product.CC_BILL_PAYMENT), 0.001);
    }

    @Test
    void neutral_fdGets_plus5() {
        Map<Product, Double> scores = singleProduct(Product.FIXED_DEPOSIT, 60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.NEUTRAL);

        assertEquals(65.0, adjusted.get(Product.FIXED_DEPOSIT), 0.001);
    }

    @Test
    void neutral_billPaymentsNoAdjustment() {
        Map<Product, Double> scores = singleProduct(Product.BILL_PAYMENTS, 60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.NEUTRAL);

        assertEquals(60.0, adjusted.get(Product.BILL_PAYMENTS), 0.001);
    }

    @Test
    void neutral_upiActivationNoAdjustment() {
        Map<Product, Double> scores = singleProduct(Product.UPI_ACTIVATION, 60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.NEUTRAL);

        assertEquals(60.0, adjusted.get(Product.UPI_ACTIVATION), 0.001);
    }

    // ── STRESSED: credit products blocked, FD gets big boost ─────────────────

    @Test
    void stressed_personalLoanRemoved() {
        Map<Product, Double> scores = singleProduct(Product.PERSONAL_LOAN, 60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.STRESSED);

        assertFalse(adjusted.containsKey(Product.PERSONAL_LOAN),
            "STRESSED: PERSONAL_LOAN must be removed (BLOCKED)");
    }

    @Test
    void stressed_unsecuredCardRemoved() {
        Map<Product, Double> scores = singleProduct(Product.UNSECURED_CARD, 60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.STRESSED);

        assertFalse(adjusted.containsKey(Product.UNSECURED_CARD));
    }

    @Test
    void stressed_securedCardRemoved() {
        Map<Product, Double> scores = singleProduct(Product.SECURED_CARD, 60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.STRESSED);

        assertFalse(adjusted.containsKey(Product.SECURED_CARD));
    }

    @Test
    void stressed_ccBillPaymentRemoved() {
        Map<Product, Double> scores = singleProduct(Product.CC_BILL_PAYMENT, 60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.STRESSED);

        assertFalse(adjusted.containsKey(Product.CC_BILL_PAYMENT));
    }

    @Test
    void stressed_fdGets_plus15() {
        Map<Product, Double> scores = singleProduct(Product.FIXED_DEPOSIT, 50.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.STRESSED);

        assertEquals(65.0, adjusted.get(Product.FIXED_DEPOSIT), 0.001);
    }

    @Test
    void stressed_billPaymentsGets_plus10() {
        Map<Product, Double> scores = singleProduct(Product.BILL_PAYMENTS, 50.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.STRESSED);

        assertEquals(60.0, adjusted.get(Product.BILL_PAYMENTS), 0.001);
    }

    @Test
    void stressed_nonCreditProductsRemain() {
        Map<Product, Double> scores = allProductsAt(60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, HealthTier.STRESSED);

        assertTrue(adjusted.containsKey(Product.UPI_ACTIVATION));
        assertTrue(adjusted.containsKey(Product.FIXED_DEPOSIT));
        assertTrue(adjusted.containsKey(Product.BILL_PAYMENTS));
        assertTrue(adjusted.containsKey(Product.RECHARGES));
        assertTrue(adjusted.containsKey(Product.REFERRALS));
        assertTrue(adjusted.containsKey(Product.RENT_PAYMENT));
        assertTrue(adjusted.containsKey(Product.FLIGHTS));
        assertEquals(7, adjusted.size(), "STRESSED: only 7 non-credit products should remain");
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void nullTierTreatedAsHealthy() {
        Map<Product, Double> scores = allProductsAt(60.0);
        Map<Product, Double> adjusted = adjuster.adjust(scores, null);

        assertEquals(11, adjusted.size());
    }

    @Test
    void emptyInputReturnsEmpty() {
        Map<Product, Double> adjusted = adjuster.adjust(new HashMap<>(), HealthTier.NEUTRAL);
        assertTrue(adjusted.isEmpty());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<Product, Double> allProductsAt(double score) {
        Map<Product, Double> scores = new HashMap<>();
        for (Product p : Product.values()) scores.put(p, score);
        return scores;
    }

    private Map<Product, Double> singleProduct(Product product, double score) {
        Map<Product, Double> scores = new HashMap<>();
        scores.put(product, score);
        return scores;
    }
}
