package supermoney.recommendation.api.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import supermoney.recommendation.api.config.ApiConfig;
import supermoney.recommendation.api.fatigue.FatigueWriter;
import supermoney.recommendation.api.store.RecommendationStore;
import supermoney.recommendation.api.store.UserRecoRecord;
import supermoney.recommendation.common.config.ConfigLoader;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.ScoredCandidate;
import supermoney.recommendation.common.model.Surface;
import supermoney.recommendation.common.pipeline.FatigueEvaluator;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock private RecommendationStore store;
    @Mock private FatigueWriter fatigueWriter;
    @Mock private ApiConfig apiConfig;

    private static ScoringConfig scoringConfig;
    private static SurfaceAffinityApplier surfaceAffinityApplier;
    private static FatigueEvaluator fatigueEvaluator;

    @BeforeAll
    static void loadConfig() {
        scoringConfig          = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        surfaceAffinityApplier = new SurfaceAffinityApplier(scoringConfig);
        fatigueEvaluator       = new FatigueEvaluator(scoringConfig);
    }

    @Test
    void returnsEmptyForUnknownUser() {
        when(store.findByUserId("unknown")).thenReturn(Optional.empty());
        RecommendationService service = buildService();

        assertTrue(service.getRecommendation("unknown", Surface.HOME_BOTTOMSHEET).isEmpty());
    }

    @Test
    void returnsEmptyWhenNoCandidates() {
        UserRecoRecord record = buildRecord("u001", List.of());
        when(store.findByUserId("u001")).thenReturn(Optional.of(record));
        RecommendationService service = buildService();

        assertTrue(service.getRecommendation("u001", Surface.HOME_BOTTOMSHEET).isEmpty());
    }

    @Test
    void returnsTopCandidateForValidUser() {
        UserRecoRecord record = buildRecord("u001", List.of(
                new ScoredCandidate(Product.PERSONAL_LOAN, 75.0, 80.0, HealthTier.HEALTHY, List.of("income_signal")),
                new ScoredCandidate(Product.FIXED_DEPOSIT, 60.0, 65.0, HealthTier.HEALTHY, List.of("balance"))
        ));
        when(store.findByUserId("u001")).thenReturn(Optional.of(record));
        when(apiConfig.getCreativeVariant(any())).thenReturn("loan_v1");

        RecommendationService service = buildService();
        Optional<RecommendationService.RecommendationResult> result =
                service.getRecommendation("u001", Surface.HOME_BOTTOMSHEET);

        assertTrue(result.isPresent());
        assertNotNull(result.get().candidate());
        assertTrue(result.get().surfaceScore() > 0);
    }

    @Test
    void fatigueWriterCalledAfterSuccessfulResult() {
        UserRecoRecord record = buildRecord("u002", List.of(
                new ScoredCandidate(Product.UPI_ACTIVATION, 85.0, 90.0, HealthTier.HEALTHY, List.of())
        ));
        when(store.findByUserId("u002")).thenReturn(Optional.of(record));
        when(apiConfig.getCreativeVariant(any())).thenReturn("upi_v1");

        RecommendationService service = buildService();
        service.getRecommendation("u002", Surface.HOME_BOTTOMSHEET);

        verify(fatigueWriter, times(1)).recordImpression(eq("u002"), any(Product.class));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private RecommendationService buildService() {
        return new RecommendationService(store, fatigueWriter, surfaceAffinityApplier,
                fatigueEvaluator, apiConfig);
    }

    private UserRecoRecord buildRecord(String userId, List<ScoredCandidate> candidates) {
        UserRecoRecord r = new UserRecoRecord();
        r.setUserId(userId);
        r.setHealthTier(HealthTier.HEALTHY);
        r.setComputedAt("2026-04-08T02:00:00Z");
        r.setCandidates(candidates);
        return r;
    }
}
