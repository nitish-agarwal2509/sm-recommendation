package supermoney.recommendation.common.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One scoring signal within a product's propensity scorer.
 * Each signal maps to a column in UserFeatures (or a derived expression).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SignalConfig {

    private String name;    // human-readable label, e.g. "income_level"
    private String column;  // UserFeatures field name (camelCase), e.g. "calculatedIncomeAmountV4"
    private double weight;  // relative weight in the weighted sum
    private Double min;     // normalization lower bound (null for binary signals)
    private Double max;     // normalization upper bound (null for binary signals)
    private String type;    // "numeric" (default) | "binary" | "inverted" (1 - normalize)

    public SignalConfig() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getColumn() { return column; }
    public void setColumn(String column) { this.column = column; }

    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }

    public Double getMin() { return min; }
    public void setMin(Double min) { this.min = min; }

    public Double getMax() { return max; }
    public void setMax(Double max) { this.max = max; }

    public String getType() { return type != null ? type : "numeric"; }
    public void setType(String type) { this.type = type; }
}
