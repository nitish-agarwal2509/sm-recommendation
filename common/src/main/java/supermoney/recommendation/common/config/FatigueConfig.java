package supermoney.recommendation.common.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Frequency capping config for a product.
 * Controls how aggressively fatigue penalty scales with impression count.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FatigueConfig {

    /**
     * Number of impressions after which the product gets the maximum fatigue penalty (70).
     * Penalty scales linearly from 0 to 70 as shown_count goes from 0 to maxImpressions.
     */
    private int maxImpressions;

    public FatigueConfig() {}

    public int getMaxImpressions() { return maxImpressions; }
    public void setMaxImpressions(int maxImpressions) { this.maxImpressions = maxImpressions; }
}
