package supermoney.recommendation.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds recommendation.* from application.yaml.
 * Registered via @EnableConfigurationProperties on SmRecommendationApiApplication.
 */
@ConfigurationProperties(prefix = "recommendation")
public class ApiConfig {

    private Store store = new Store();
    private String scoringConfigPath;
    private Map<String, String> creativeVariants = new HashMap<>();

    public Store getStore() { return store; }
    public void setStore(Store store) { this.store = store; }

    public String getScoringConfigPath() { return scoringConfigPath; }
    public void setScoringConfigPath(String scoringConfigPath) { this.scoringConfigPath = scoringConfigPath; }

    public Map<String, String> getCreativeVariants() { return creativeVariants; }
    public void setCreativeVariants(Map<String, String> creativeVariants) { this.creativeVariants = creativeVariants; }

    public String getCreativeVariant(String productName) {
        return creativeVariants.getOrDefault(productName, "default_v1");
    }

    public static class Store {
        private String localJsonPath;

        public String getLocalJsonPath() { return localJsonPath; }
        public void setLocalJsonPath(String localJsonPath) { this.localJsonPath = localJsonPath; }
    }
}
