package supermoney.recommendation.common;

import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.config.ConfigLoader;
import supermoney.recommendation.common.config.ProductConfig;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.config.SignalConfig;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    private static final String[] ALL_PRODUCTS = {
        "UPI_ACTIVATION", "PERSONAL_LOAN", "UNSECURED_CARD", "SECURED_CARD",
        "CC_BILL_PAYMENT", "RENT_PAYMENT", "BILL_PAYMENTS", "RECHARGES",
        "REFERRALS", "FIXED_DEPOSIT", "FLIGHTS"
    };

    @Test
    void loadsAllProductsFromClasspath() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        assertNotNull(config.getProducts(), "products map must not be null");
        assertEquals(11, config.getProducts().size(), "all 11 products must be present");
    }

    @Test
    void allProductsPresentByName() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        for (String product : ALL_PRODUCTS) {
            assertNotNull(config.getProduct(product), "Missing config for product: " + product);
        }
    }

    @Test
    void allProductsHavePositiveTotalSignalWeight() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        for (String product : ALL_PRODUCTS) {
            ProductConfig pc = config.getProduct(product);
            List<SignalConfig> signals = pc.getSignals();
            assertNotNull(signals, "Signals must not be null for: " + product);
            assertFalse(signals.isEmpty(), "Signals must not be empty for: " + product);
            double totalWeight = signals.stream().mapToDouble(SignalConfig::getWeight).sum();
            assertTrue(totalWeight > 0, "Total weight must be > 0 for: " + product);
        }
    }

    @Test
    void allProductsHaveValidBusinessPriority() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        for (String product : ALL_PRODUCTS) {
            double priority = config.getProduct(product).getBusinessPriority();
            assertTrue(priority > 0 && priority <= 1.0,
                "Business priority must be in (0, 1] for: " + product + ", got: " + priority);
        }
    }

    @Test
    void allProductsHavePositiveMaxImpressions() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        for (String product : ALL_PRODUCTS) {
            int maxImpressions = config.getProduct(product).getFatigue().getMaxImpressions();
            assertTrue(maxImpressions > 0, "max_impressions must be > 0 for: " + product);
        }
    }

    @Test
    void healthAdjustmentsReturnCorrectValues() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");

        // PERSONAL_LOAN: HEALTHY=0, NEUTRAL=-10, STRESSED=BLOCKED
        ProductConfig loanConfig = config.getProduct("PERSONAL_LOAN");
        assertEquals(0.0, loanConfig.getHealthAdjustment("HEALTHY"));
        assertEquals(-10.0, loanConfig.getHealthAdjustment("NEUTRAL"));
        assertNull(loanConfig.getHealthAdjustment("STRESSED"), "STRESSED should return null (BLOCKED)");

        // FIXED_DEPOSIT: HEALTHY=0, NEUTRAL=+5, STRESSED=+15
        ProductConfig fdConfig = config.getProduct("FIXED_DEPOSIT");
        assertEquals(0.0, fdConfig.getHealthAdjustment("HEALTHY"));
        assertEquals(5.0, fdConfig.getHealthAdjustment("NEUTRAL"));
        assertEquals(15.0, fdConfig.getHealthAdjustment("STRESSED"));
    }

    @Test
    void surfaceAffinityLoadedForAllSurfaces() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        assertNotNull(config.getSurfaceAffinity(), "surface_affinity must not be null");

        String[] surfaces = {"HOME_BANNER", "HOME_BOTTOMSHEET", "POST_UPI", "CASHBACK_REDEEMED", "REWARDS_HISTORY"};
        for (String surface : surfaces) {
            Map<String, Double> affinity = config.getSurfaceAffinity().get(surface);
            assertNotNull(affinity, "Missing surface affinity for: " + surface);
            assertEquals(11, affinity.size(), "All 11 products must have affinity for surface: " + surface);
        }
    }

    @Test
    void surfaceAffinityValuesInValidRange() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        config.getSurfaceAffinity().forEach((surface, products) ->
            products.forEach((product, multiplier) -> {
                assertTrue(multiplier >= 0.0 && multiplier <= 1.0,
                    "Affinity for " + surface + "/" + product + " out of range: " + multiplier);
            })
        );
    }

    @Test
    void upiActivationHasHighestBusinessPriority() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        double upiPriority = config.getProduct("UPI_ACTIVATION").getBusinessPriority();
        for (String product : ALL_PRODUCTS) {
            if (!product.equals("UPI_ACTIVATION")) {
                assertTrue(upiPriority >= config.getProduct(product).getBusinessPriority(),
                    "UPI_ACTIVATION must have highest priority, but " + product + " has equal or higher");
            }
        }
    }
}
