package supermoney.recommendation.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads and validates scoring_rules.yaml.
 * Used by both the Flink processor (loads from file path) and
 * the Spring Boot API (loads from classpath).
 *
 * Thread-safe: load() returns an immutable config; callers should hold a reference.
 */
public class ConfigLoader {

    private static final ObjectMapper YAML_MAPPER;

    static {
        YAML_MAPPER = new ObjectMapper(new YAMLFactory());
        // Map snake_case YAML keys (e.g. max_impressions) to camelCase Java fields (maxImpressions)
        YAML_MAPPER.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    private ConfigLoader() {}

    /** Load from an absolute file path. Used by the Flink job. */
    public static ScoringConfig loadFromFile(String filePath) {
        try {
            ScoringConfig config = YAML_MAPPER.readValue(new File(filePath), ScoringConfig.class);
            validate(config, filePath);
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load scoring config from: " + filePath, e);
        }
    }

    /** Load from classpath resource. Used by the Spring Boot API. */
    public static ScoringConfig loadFromClasspath(String resourcePath) {
        try (InputStream is = ConfigLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Scoring config not found on classpath: " + resourcePath);
            }
            ScoringConfig config = YAML_MAPPER.readValue(is, ScoringConfig.class);
            validate(config, resourcePath);
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load scoring config from classpath: " + resourcePath, e);
        }
    }

    private static void validate(ScoringConfig config, String source) {
        if (config.getProducts() == null || config.getProducts().isEmpty()) {
            throw new IllegalStateException("scoring_rules.yaml from [" + source + "] has no products defined");
        }

        String[] expectedProducts = {
            "UPI_ACTIVATION", "PERSONAL_LOAN", "UNSECURED_CARD", "SECURED_CARD",
            "CC_BILL_PAYMENT", "RENT_PAYMENT", "BILL_PAYMENTS", "RECHARGES",
            "REFERRALS", "FIXED_DEPOSIT", "FLIGHTS"
        };

        for (String product : expectedProducts) {
            ProductConfig pc = config.getProducts().get(product);
            if (pc == null) {
                throw new IllegalStateException("Missing product config for: " + product + " in " + source);
            }
            if (pc.getSignals() == null || pc.getSignals().isEmpty()) {
                throw new IllegalStateException("No signals defined for product: " + product);
            }
            double totalWeight = pc.getSignals().stream().mapToDouble(s -> s.getWeight()).sum();
            if (totalWeight <= 0) {
                throw new IllegalStateException("Total signal weight is zero for product: " + product);
            }
            if (pc.getFatigue() == null || pc.getFatigue().getMaxImpressions() <= 0) {
                throw new IllegalStateException("Invalid fatigue config for product: " + product);
            }
        }
    }
}
