package supermoney.recommendation.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void scoredCandidateRoundTrip() throws Exception {
        ScoredCandidate original = new ScoredCandidate(
            Product.PERSONAL_LOAN,
            72.5,
            83.2,
            HealthTier.NEUTRAL,
            List.of("income_signal", "clean_history")
        );

        String json = MAPPER.writeValueAsString(original);
        ScoredCandidate deserialized = MAPPER.readValue(json, ScoredCandidate.class);

        assertEquals(original.getProduct(), deserialized.getProduct());
        assertEquals(original.getPropensityScore(), deserialized.getPropensityScore(), 0.001);
        assertEquals(original.getPreSurfaceScore(), deserialized.getPreSurfaceScore(), 0.001);
        assertEquals(original.getHealthTier(), deserialized.getHealthTier());
        assertEquals(original.getReasonTokens(), deserialized.getReasonTokens());
    }

    @Test
    void recommendationRoundTrip() throws Exception {
        Recommendation original = new Recommendation();
        original.setUserId("u123");
        original.setSurface(Surface.HOME_BOTTOMSHEET);
        original.setProduct(Product.PERSONAL_LOAN);
        original.setFinalScore(83.2);
        original.setHealthTier(HealthTier.NEUTRAL);
        original.setReasonTokens(List.of("income_signal", "credit_seeking_intent"));
        original.setCreativeVariant("loan_v1");
        original.setComputedAt("2026-04-08T02:00:00Z");

        String json = MAPPER.writeValueAsString(original);
        Recommendation deserialized = MAPPER.readValue(json, Recommendation.class);

        assertEquals(original.getUserId(), deserialized.getUserId());
        assertEquals(original.getSurface(), deserialized.getSurface());
        assertEquals(original.getProduct(), deserialized.getProduct());
        assertEquals(original.getFinalScore(), deserialized.getFinalScore(), 0.001);
        assertEquals(original.getHealthTier(), deserialized.getHealthTier());
        assertEquals(original.getReasonTokens(), deserialized.getReasonTokens());
        assertEquals(original.getCreativeVariant(), deserialized.getCreativeVariant());
        assertEquals(original.getComputedAt(), deserialized.getComputedAt());
    }

    @Test
    void fatigueDataRoundTrip() throws Exception {
        FatigueData original = new FatigueData(2, "2026-04-07T14:30:00Z", false);

        String json = MAPPER.writeValueAsString(original);
        FatigueData deserialized = MAPPER.readValue(json, FatigueData.class);

        assertEquals(original.getShownCount(), deserialized.getShownCount());
        assertEquals(original.getShownAt(), deserialized.getShownAt());
        assertEquals(original.isConverted(), deserialized.isConverted());
    }

    @Test
    void userFeaturesNullFieldsSerializeCorrectly() throws Exception {
        UserFeatures features = new UserFeatures();
        features.setCustomerId("u001");
        features.setCalculatedIncomeAmountV4(50000.0);
        // leave all other fields null

        String json = MAPPER.writeValueAsString(features);
        UserFeatures deserialized = MAPPER.readValue(json, UserFeatures.class);

        assertEquals("u001", deserialized.getCustomerId());
        assertEquals(50000.0, deserialized.getCalculatedIncomeAmountV4(), 0.001);
        assertNull(deserialized.getHealthTier());
        assertNull(deserialized.getCntCcAcc());
    }

    @Test
    void healthTierEnumSerializes() throws Exception {
        String json = MAPPER.writeValueAsString(HealthTier.STRESSED);
        assertEquals("\"STRESSED\"", json);
        assertEquals(HealthTier.STRESSED, MAPPER.readValue(json, HealthTier.class));
    }

    @Test
    void productEnumSerializes() throws Exception {
        for (Product p : Product.values()) {
            String json = MAPPER.writeValueAsString(p);
            assertEquals(p, MAPPER.readValue(json, Product.class));
        }
    }

    @Test
    void surfaceEnumSerializes() throws Exception {
        for (Surface s : Surface.values()) {
            String json = MAPPER.writeValueAsString(s);
            assertEquals(s, MAPPER.readValue(json, Surface.class));
        }
    }
}
