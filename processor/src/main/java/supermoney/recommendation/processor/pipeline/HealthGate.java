package supermoney.recommendation.processor.pipeline;

import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.UserFeatures;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stage 2: Health Policy Gate.
 * Runs BEFORE scoring — STRESSED users never reach credit product scorers.
 *
 * HEALTHY  → no products blocked
 * NEUTRAL  → no products blocked (penalty applied in scoring stage)
 * STRESSED → PERSONAL_LOAN, UNSECURED_CARD, SECURED_CARD, CC_BILL_PAYMENT blocked
 */
public class HealthGate {

    private static final Set<Product> STRESSED_BLOCKED = Set.of(
        Product.PERSONAL_LOAN,
        Product.UNSECURED_CARD,
        Product.SECURED_CARD,
        Product.CC_BILL_PAYMENT
    );

    /**
     * Filters the candidate list based on health tier.
     * Returns the surviving candidates after the health gate.
     */
    public List<Product> filter(UserFeatures features, List<Product> candidates) {
        HealthTier tier = features.getHealthTier();
        if (tier == null || tier != HealthTier.STRESSED) {
            return candidates;
        }
        return candidates.stream()
            .filter(p -> !STRESSED_BLOCKED.contains(p))
            .collect(Collectors.toList());
    }

    /** All product candidates to start the pipeline with. */
    public static List<Product> allProducts() {
        return Arrays.asList(Product.values());
    }
}
