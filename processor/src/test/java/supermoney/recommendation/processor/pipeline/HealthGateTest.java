package supermoney.recommendation.processor.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.UserFeatures;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HealthGateTest {

    private HealthGate gate;

    @BeforeEach
    void setUp() { gate = new HealthGate(); }

    @Test
    void stressedUserBlocksCreditProducts() {
        UserFeatures f = new UserFeatures();
        f.setHealthTier(HealthTier.STRESSED);

        List<Product> result = gate.filter(f, HealthGate.allProducts());

        assertFalse(result.contains(Product.PERSONAL_LOAN), "STRESSED: PERSONAL_LOAN must be blocked");
        assertFalse(result.contains(Product.UNSECURED_CARD), "STRESSED: UNSECURED_CARD must be blocked");
        assertFalse(result.contains(Product.SECURED_CARD), "STRESSED: SECURED_CARD must be blocked");
        assertFalse(result.contains(Product.CC_BILL_PAYMENT), "STRESSED: CC_BILL_PAYMENT must be blocked");
    }

    @Test
    void stressedUserKeepsNonCreditProducts() {
        UserFeatures f = new UserFeatures();
        f.setHealthTier(HealthTier.STRESSED);

        List<Product> result = gate.filter(f, HealthGate.allProducts());

        assertTrue(result.contains(Product.FIXED_DEPOSIT));
        assertTrue(result.contains(Product.BILL_PAYMENTS));
        assertTrue(result.contains(Product.RECHARGES));
        assertTrue(result.contains(Product.REFERRALS));
        assertTrue(result.contains(Product.UPI_ACTIVATION));
        assertTrue(result.contains(Product.RENT_PAYMENT));
        assertTrue(result.contains(Product.FLIGHTS));
    }

    @Test
    void neutralUserPassesAllProducts() {
        UserFeatures f = new UserFeatures();
        f.setHealthTier(HealthTier.NEUTRAL);

        List<Product> result = gate.filter(f, HealthGate.allProducts());

        assertEquals(11, result.size(), "NEUTRAL: all 11 products should pass");
    }

    @Test
    void healthyUserPassesAllProducts() {
        UserFeatures f = new UserFeatures();
        f.setHealthTier(HealthTier.HEALTHY);

        List<Product> result = gate.filter(f, HealthGate.allProducts());

        assertEquals(11, result.size(), "HEALTHY: all 11 products should pass");
    }

    @Test
    void stressedUserHas7ProductsRemaining() {
        UserFeatures f = new UserFeatures();
        f.setHealthTier(HealthTier.STRESSED);

        List<Product> result = gate.filter(f, HealthGate.allProducts());

        assertEquals(7, result.size(), "STRESSED: 4 blocked → 7 remaining");
    }

    @Test
    void nullTierTreatedAsNonStressed() {
        UserFeatures f = new UserFeatures();
        f.setHealthTier(null);

        List<Product> result = gate.filter(f, HealthGate.allProducts());

        assertEquals(11, result.size(), "Null tier: all products should pass");
    }

    @Test
    void allProductsStarterContains11Products() {
        assertEquals(11, HealthGate.allProducts().size());
    }
}
