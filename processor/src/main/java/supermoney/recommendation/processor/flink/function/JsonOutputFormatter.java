package supermoney.recommendation.processor.flink.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.apache.flink.api.common.functions.MapFunction;
import supermoney.recommendation.processor.flink.model.RecoResult;

/**
 * Serializes a RecoResult to a single JSON line (JSONL format).
 * Uses snake_case field naming to match the output schema in the PRD.
 *
 * Output schema per line:
 * {
 *   "user_id":      "u001",
 *   "health_tier":  "HEALTHY",
 *   "computed_at":  "2026-04-08T02:00:00Z",
 *   "candidates": [
 *     { "product": "PERSONAL_LOAN", "propensity_score": 72.5,
 *       "pre_surface_score": 83.2, "health_tier": "HEALTHY",
 *       "reason_tokens": ["income_signal", "clean_history"] }
 *   ]
 * }
 */
public class JsonOutputFormatter implements MapFunction<RecoResult, String> {

    private static final ObjectMapper MAPPER;
    static {
        MAPPER = new ObjectMapper();
        MAPPER.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Override
    public String map(RecoResult result) throws Exception {
        return MAPPER.writeValueAsString(result);
    }
}
