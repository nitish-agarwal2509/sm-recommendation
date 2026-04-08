package supermoney.recommendation.common.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Full configuration for one product: scoring signals, eligibility rules,
 * fatigue cap, business priority, and health adjustments.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductConfig {

    private double businessPriority;                    // 0–1, used in final ranking formula
    private List<SignalConfig> signals;                 // propensity scoring signals
    private EligibilityConfig eligibility;              // hard eligibility thresholds
    private FatigueConfig fatigue;                      // frequency capping config
    private Map<String, Object> healthAdjustments;      // "HEALTHY"->0, "NEUTRAL"->-10, "STRESSED"->"BLOCKED"

    public ProductConfig() {}

    public double getBusinessPriority() { return businessPriority; }
    public void setBusinessPriority(double businessPriority) { this.businessPriority = businessPriority; }

    public List<SignalConfig> getSignals() { return signals; }
    public void setSignals(List<SignalConfig> signals) { this.signals = signals; }

    public EligibilityConfig getEligibility() { return eligibility; }
    public void setEligibility(EligibilityConfig eligibility) { this.eligibility = eligibility; }

    public FatigueConfig getFatigue() { return fatigue; }
    public void setFatigue(FatigueConfig fatigue) { this.fatigue = fatigue; }

    public Map<String, Object> getHealthAdjustments() { return healthAdjustments; }
    public void setHealthAdjustments(Map<String, Object> healthAdjustments) { this.healthAdjustments = healthAdjustments; }

    /**
     * Returns the health adjustment value for the given tier.
     * Returns null if the product is BLOCKED for that tier.
     */
    public Double getHealthAdjustment(String tier) {
        if (healthAdjustments == null) return 0.0;
        Object val = healthAdjustments.get(tier);
        if (val == null) return 0.0;
        if ("BLOCKED".equals(val.toString())) return null; // null signals blocked
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return 0.0; }
    }
}
