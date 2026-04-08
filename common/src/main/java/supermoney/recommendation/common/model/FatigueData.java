package supermoney.recommendation.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Impression tracking data per product per user.
 * Written by the Serving API on every GET /recommendation response.
 * Read by the Flink batch job on the next run to incorporate into final_score.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FatigueData {

    private int shownCount;
    private String shownAt;    // ISO-8601 timestamp, e.g. "2026-04-08T14:30:00Z"
    private boolean converted; // true = user already has this product → permanent exclusion

    public FatigueData() {}

    public FatigueData(int shownCount, String shownAt, boolean converted) {
        this.shownCount = shownCount;
        this.shownAt = shownAt;
        this.converted = converted;
    }

    public int getShownCount() { return shownCount; }
    public void setShownCount(int shownCount) { this.shownCount = shownCount; }

    public String getShownAt() { return shownAt; }
    public void setShownAt(String shownAt) { this.shownAt = shownAt; }

    public boolean isConverted() { return converted; }
    public void setConverted(boolean converted) { this.converted = converted; }

    @Override
    public String toString() {
        return "FatigueData{shownCount=" + shownCount + ", shownAt='" + shownAt + "', converted=" + converted + "}";
    }
}
