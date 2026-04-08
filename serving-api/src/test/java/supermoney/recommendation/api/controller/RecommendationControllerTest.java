package supermoney.recommendation.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import supermoney.recommendation.api.service.RecommendationService;
import supermoney.recommendation.api.service.RecommendationService.RecommendationResult;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.ScoredCandidate;
import supermoney.recommendation.common.model.Surface;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RecommendationController.class)
class RecommendationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RecommendationService service;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validRequestReturns200WithRecommendation() throws Exception {
        ScoredCandidate candidate = new ScoredCandidate(
                Product.PERSONAL_LOAN, 75.0, 80.0, HealthTier.HEALTHY,
                List.of("income_signal", "clean_history"));

        when(service.getRecommendation(eq("u001"), eq(Surface.HOME_BOTTOMSHEET)))
                .thenReturn(Optional.of(new RecommendationResult(candidate, 80.0, "2026-04-08T02:00:00Z", "loan_v1")));

        MvcResult result = mockMvc.perform(get("/recommendation")
                        .param("user_id", "u001")
                        .param("surface", "HOME_BOTTOMSHEET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("u001"))
                .andExpect(jsonPath("$.surface").value("HOME_BOTTOMSHEET"))
                .andExpect(jsonPath("$.recommendation.product").value("PERSONAL_LOAN"))
                .andExpect(jsonPath("$.recommendation.health_tier").value("HEALTHY"))
                .andExpect(jsonPath("$.recommendation.creative_variant").value("loan_v1"))
                .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("recommendation").has("final_score"), "Response must include final_score");
        assertTrue(body.get("recommendation").has("computed_at"), "Response must include computed_at");
    }

    @Test
    void unknownUserReturns404() throws Exception {
        when(service.getRecommendation(eq("unknown"), any(Surface.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/recommendation")
                        .param("user_id", "unknown")
                        .param("surface", "HOME_BOTTOMSHEET"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NO_RECOMMENDATION"));
    }

    @Test
    void invalidSurfaceReturns400() throws Exception {
        mockMvc.perform(get("/recommendation")
                        .param("user_id", "u001")
                        .param("surface", "INVALID_SURFACE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SURFACE"));
    }

    @Test
    void missingUserIdReturns400() throws Exception {
        mockMvc.perform(get("/recommendation")
                        .param("surface", "HOME_BOTTOMSHEET"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingSurfaceReturns400() throws Exception {
        mockMvc.perform(get("/recommendation")
                        .param("user_id", "u001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void surfaceParamIsCaseInsensitive() throws Exception {
        ScoredCandidate candidate = new ScoredCandidate(
                Product.RECHARGES, 55.0, 60.0, HealthTier.NEUTRAL, List.of());

        when(service.getRecommendation(eq("u007"), eq(Surface.POST_UPI)))
                .thenReturn(Optional.of(new RecommendationResult(candidate, 48.0, "2026-04-08T02:00:00Z", "recharge_v1")));

        mockMvc.perform(get("/recommendation")
                        .param("user_id", "u007")
                        .param("surface", "post_upi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.product").value("RECHARGES"));
    }
}
