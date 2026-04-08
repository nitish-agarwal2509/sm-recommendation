package supermoney.recommendation.api.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.config.ConfigLoader;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.ScoredCandidate;
import supermoney.recommendation.common.model.Surface;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SurfaceAffinityApplierTest {

    private static SurfaceAffinityApplier applier;

    @BeforeAll
    static void setup() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        applier = new SurfaceAffinityApplier(config);
    }

    @Test
    void emptyListReturnsEmpty() {
        Optional<SurfaceAffinityApplier.RankedCandidate> result =
                applier.applyAndRank(List.of(), Surface.HOME_BOTTOMSHEET, Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void nullListReturnsEmpty() {
        Optional<SurfaceAffinityApplier.RankedCandidate> result =
                applier.applyAndRank(null, Surface.HOME_BOTTOMSHEET, Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void singleCandidateIsReturned() {
        ScoredCandidate c = candidate(Product.FIXED_DEPOSIT, 60.0);
        Optional<SurfaceAffinityApplier.RankedCandidate> result =
                applier.applyAndRank(List.of(c), Surface.HOME_BOTTOMSHEET, Map.of());
        assertTrue(result.isPresent());
        assertEquals(Product.FIXED_DEPOSIT, result.get().candidate().getProduct());
    }

    @Test
    void surfaceAffinityFlipsRankingBetweenSurfaces() {
        // PERSONAL_LOAN affinity: HOME_BANNER=0.8, POST_UPI=0.4
        // REFERRALS affinity:     HOME_BANNER=0.6, POST_UPI=0.8
        // Equal pre_surface_score → PERSONAL_LOAN wins HOME_BANNER, REFERRALS wins POST_UPI
        ScoredCandidate loan     = candidate(Product.PERSONAL_LOAN, 70.0);
        ScoredCandidate referral = candidate(Product.REFERRALS, 70.0);

        Optional<SurfaceAffinityApplier.RankedCandidate> homeBanner =
                applier.applyAndRank(List.of(loan, referral), Surface.HOME_BANNER, Map.of());
        assertEquals(Product.PERSONAL_LOAN, homeBanner.get().candidate().getProduct(),
                "PERSONAL_LOAN wins HOME_BANNER (affinity 0.8 > REFERRALS 0.6)");

        Optional<SurfaceAffinityApplier.RankedCandidate> postUpi =
                applier.applyAndRank(List.of(loan, referral), Surface.POST_UPI, Map.of());
        assertEquals(Product.REFERRALS, postUpi.get().candidate().getProduct(),
                "REFERRALS wins POST_UPI (affinity 0.8 > PERSONAL_LOAN 0.4)");
    }

    @Test
    void highFatiguePenaltyReducesScore() {
        ScoredCandidate loan  = candidate(Product.PERSONAL_LOAN, 80.0);
        ScoredCandidate recharge = candidate(Product.RECHARGES, 60.0);

        // Give PERSONAL_LOAN a heavy fatigue penalty
        Map<Product, Double> penalties = Map.of(Product.PERSONAL_LOAN, 70.0);

        Optional<SurfaceAffinityApplier.RankedCandidate> result =
                applier.applyAndRank(List.of(loan, recharge), Surface.HOME_BOTTOMSHEET, penalties);

        // loan pre_surface=80, penalty=70, affinity=1.0 → (80 - 70*0.15)*1.0 = (80-10.5) = 69.5
        // recharge pre_surface=60, no penalty, affinity=0.8 → 60*0.8 = 48
        // loan still wins here, but penalty reduced its score
        assertTrue(result.isPresent());
        assertEquals(Product.PERSONAL_LOAN, result.get().candidate().getProduct());
        // Score should be reduced vs without penalty
        assertTrue(result.get().surfaceScore() < 80.0);
    }

    @Test
    void fdPreferredOverFlightsWhenScoresClose() {
        // FD affinity on HOME_BOTTOMSHEET = 0.9, FLIGHTS = 0.7
        // Equal pre_surface_score → FD wins (higher affinity)
        ScoredCandidate fd = candidate(Product.FIXED_DEPOSIT, 60.0);
        ScoredCandidate flights = candidate(Product.FLIGHTS, 60.0);

        Optional<SurfaceAffinityApplier.RankedCandidate> result =
                applier.applyAndRank(List.of(fd, flights), Surface.HOME_BOTTOMSHEET, Map.of());
        assertEquals(Product.FIXED_DEPOSIT, result.get().candidate().getProduct(),
                "FD should beat FLIGHTS when pre_surface_scores are equal (FD affinity 0.9 > FLIGHTS 0.7)");
    }

    private ScoredCandidate candidate(Product product, double preSurfaceScore) {
        return new ScoredCandidate(product, preSurfaceScore * 0.8, preSurfaceScore,
                HealthTier.HEALTHY, List.of());
    }
}
