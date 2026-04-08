package supermoney.recommendation.common.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Top-level YAML config object.
 * Loaded once at startup by ConfigLoader from scoring_rules.yaml.
 *
 * Structure:
 *   products:
 *     PERSONAL_LOAN: { ... }
 *     UNSECURED_CARD: { ... }
 *     ...
 *   surface_affinity:
 *     HOME_BANNER:
 *       PERSONAL_LOAN: 0.8
 *       ...
 *     ...
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScoringConfig {

    /** Keyed by Product enum name (e.g. "PERSONAL_LOAN") */
    private Map<String, ProductConfig> products;

    /** surface_affinity[surfaceName][productName] = multiplier (0–1) */
    private Map<String, Map<String, Double>> surfaceAffinity;

    public ScoringConfig() {}

    public Map<String, ProductConfig> getProducts() { return products; }
    public void setProducts(Map<String, ProductConfig> products) { this.products = products; }

    public Map<String, Map<String, Double>> getSurfaceAffinity() { return surfaceAffinity; }
    public void setSurfaceAffinity(Map<String, Map<String, Double>> surfaceAffinity) { this.surfaceAffinity = surfaceAffinity; }

    public ProductConfig getProduct(String productName) {
        return products != null ? products.get(productName) : null;
    }

    public double getSurfaceAffinityMultiplier(String surface, String product) {
        if (surfaceAffinity == null) return 1.0;
        Map<String, Double> productMap = surfaceAffinity.get(surface);
        if (productMap == null) return 1.0;
        return productMap.getOrDefault(product, 1.0);
    }
}
