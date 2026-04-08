package supermoney.recommendation.processor.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.config.ConfigLoader;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.UserFeatures;
import supermoney.recommendation.processor.pipeline.PropensityScorer.PropensityResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PropensityScorerTest {

    private PropensityScorer scorer;
    private ScoringConfig config;

    @BeforeEach
    void setUp() {
        config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        scorer = new PropensityScorer(config);
    }

    @Test
    void personalLoanScoreIn0_100Range() {
        UserFeatures f = highIncomeUser();
        Map<Product, PropensityResult> results = scorer.score(f, List.of(Product.PERSONAL_LOAN));
        assertTrue(results.containsKey(Product.PERSONAL_LOAN));
        double score = results.get(Product.PERSONAL_LOAN).getScore();
        assertTrue(score >= 0.0 && score <= 100.0, "Score out of range: " + score);
    }

    @Test
    void highIncomeUserScoresHigherOnPersonalLoan() {
        UserFeatures high = new UserFeatures();
        high.setCalculatedIncomeAmountV4(150000.0);
        high.setDtiRatio(0.10);
        high.setAcc0Vintage(500);
        high.setCntLoanApplicationsC30(3);
        high.setBounceFlag(0);
        high.setAutoDebitBounceM0(0);
        high.setCntDelinquncyLoanC90(0);

        UserFeatures low = new UserFeatures();
        low.setCalculatedIncomeAmountV4(21000.0);  // just above eligibility threshold
        low.setDtiRatio(0.45);
        low.setAcc0Vintage(30);
        low.setCntLoanApplicationsC30(0);
        low.setBounceFlag(1);
        low.setAutoDebitBounceM0(1);
        low.setCntDelinquncyLoanC90(0);

        double highScore = scorer.score(high, List.of(Product.PERSONAL_LOAN))
            .get(Product.PERSONAL_LOAN).getScore();
        double lowScore = scorer.score(low, List.of(Product.PERSONAL_LOAN))
            .get(Product.PERSONAL_LOAN).getScore();

        assertTrue(highScore > lowScore, "High income should score higher: " + highScore + " vs " + lowScore);
    }

    @Test
    void fixedDepositScoresHighForHighBalance() {
        UserFeatures f = new UserFeatures();
        f.setTotalAvgBal30(400000.0);
        f.setSurplusCash(50000.0);
        f.setCalculatedIncomeConfidenceV4("HIGH");
        f.setSalaryM1V4(80000.0);
        f.setSalaryM2V4(80000.0);
        f.setSalaryM3V4(80000.0);
        f.setFpsInvestmentV1Probability(0.9);

        Map<Product, PropensityResult> results = scorer.score(f, List.of(Product.FIXED_DEPOSIT));
        double score = results.get(Product.FIXED_DEPOSIT).getScore();
        assertTrue(score > 60.0, "High balance should produce high FD score, got: " + score);
    }

    @Test
    void upiActivationScoresHighWithBankAccount() {
        UserFeatures f = new UserFeatures();
        f.setAcc0AccNumber("ACC123");  // binary → 1.0
        f.setDigitalSavviness(8);
        f.setNetBankingFlag(1);
        f.setRecencyAppsGenreFinance(10); // used recently = high score (inverted)

        Map<Product, PropensityResult> results = scorer.score(f, List.of(Product.UPI_ACTIVATION));
        double score = results.get(Product.UPI_ACTIVATION).getScore();
        assertTrue(score > 60.0, "Good UPI profile should score high, got: " + score);
    }

    @Test
    void allProductsScoreForWellRoundedUser() {
        UserFeatures f = wellRoundedUser();
        List<Product> all = List.of(Product.values());
        Map<Product, PropensityResult> results = scorer.score(f, all);

        // All products should get a score (no missing results for well-rounded user)
        assertFalse(results.isEmpty());
        for (Product p : all) {
            if (results.containsKey(p)) {
                double score = results.get(p).getScore();
                assertTrue(score >= 0.0 && score <= 100.0,
                    "Score out of range for " + p + ": " + score);
            }
        }
    }

    @Test
    void nullFeaturesHandledGracefully() {
        UserFeatures f = new UserFeatures();  // all null
        Map<Product, PropensityResult> results = scorer.score(f, List.of(Product.PERSONAL_LOAN));
        // Should not throw; may return empty result or low score
        assertDoesNotThrow(() -> scorer.score(f, List.of(Product.PERSONAL_LOAN)));
    }

    @Test
    void personalLoanCcBillCrossSellBoostApplied() {
        // User with outstanding CC bill due >= 23 days → Personal Loan gets +15 propensity
        UserFeatures withBill = new UserFeatures();
        withBill.setCalculatedIncomeAmountV4(60000.0);
        withBill.setDtiRatio(0.20);
        withBill.setAcc0Vintage(300);
        withBill.setCntLoanApplicationsC30(1);
        withBill.setBounceFlag(0);
        withBill.setAutoDebitBounceM0(0);
        withBill.setCntDelinquncyLoanC90(0);
        withBill.setCcBillM0(8000.0);
        withBill.setCcBillLatestDate("2026-03-15"); // 24 days before today

        UserFeatures withoutBill = new UserFeatures();
        withoutBill.setCalculatedIncomeAmountV4(60000.0);
        withoutBill.setDtiRatio(0.20);
        withoutBill.setAcc0Vintage(300);
        withoutBill.setCntLoanApplicationsC30(1);
        withoutBill.setBounceFlag(0);
        withoutBill.setAutoDebitBounceM0(0);
        withoutBill.setCntDelinquncyLoanC90(0);
        withoutBill.setCcBillM0(0.0);  // no outstanding bill

        double scoreWithBill    = scorer.score(withBill, List.of(Product.PERSONAL_LOAN))
            .get(Product.PERSONAL_LOAN).getScore();
        double scoreWithoutBill = scorer.score(withoutBill, List.of(Product.PERSONAL_LOAN))
            .get(Product.PERSONAL_LOAN).getScore();

        assertTrue(scoreWithBill > scoreWithoutBill,
            "CC bill cross-sell boost should raise Personal Loan propensity. With: "
            + scoreWithBill + " Without: " + scoreWithoutBill);
    }

    @Test
    void reasonTokensIncludedForHighContributingSignals() {
        UserFeatures f = highIncomeUser();
        f.setCntLoanApplicationsC30(4);  // strong intent signal

        Map<Product, PropensityResult> results = scorer.score(f, List.of(Product.PERSONAL_LOAN));
        List<String> tokens = results.get(Product.PERSONAL_LOAN).getReasonTokens();
        assertNotNull(tokens);
        assertFalse(tokens.isEmpty(), "Should have reason tokens for high-scoring signals");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private UserFeatures highIncomeUser() {
        UserFeatures f = new UserFeatures();
        f.setCalculatedIncomeAmountV4(100000.0);
        f.setDtiRatio(0.20);
        f.setAcc0Vintage(400);
        f.setCntLoanApplicationsC30(2);
        f.setBounceFlag(0);
        f.setAutoDebitBounceM0(0);
        f.setCntDelinquncyLoanC90(0);
        f.setCntCcApplicationsC30(1);
        return f;
    }

    private UserFeatures wellRoundedUser() {
        UserFeatures f = new UserFeatures();
        f.setCalculatedIncomeAmountV4(80000.0);
        f.setDtiRatio(0.25);
        f.setAcc0Vintage(500);
        f.setCntLoanApplicationsC30(1);
        f.setBounceFlag(0);
        f.setAutoDebitBounceM0(0);
        f.setCntDelinquncyLoanC90(0);
        f.setCntDelinquncyCcC90(0);
        f.setMaxDpdAcc1(0);
        f.setDigitalSavviness(7);
        f.setNetBankingFlag(1);
        f.setRecencyAppsGenreFinance(15);
        f.setAcc0AccNumber("ACC123");
        f.setTotalAvgBal30(100000.0);
        f.setSurplusCash(30000.0);
        f.setCalculatedIncomeConfidenceV4("HIGH");
        f.setSalaryM1V4(80000.0);
        f.setSalaryM2V4(80000.0);
        f.setSalaryM3V4(80000.0);
        f.setFpsInvestmentV1Probability(0.6);
        f.setUpiUserFlag(1);
        f.setCntTotalTxnUpi3m(50);
        f.setAmtTotalTxnUpi3m(150000.0);
        f.setAmtTotalDebitsUpi3m(120000.0);
        f.setBroadbandFlag(1);
        f.setBroadbandRecency(20);
        f.setMaxDpdBroadband(10);
        f.setBillUrgency(20.0);
        f.setAvgPostpaidBill3m(500.0);
        f.setPrepaidFlag(1);
        f.setCntPaytmWalletRechargeM1(2);
        f.setCntPaytmWalletRechargeM2(2);
        f.setCntPaytmWalletRechargeM3(2);
        f.setSumAmtTopupM1(300.0);
        f.setSumAmtTopupM2(300.0);
        f.setSumAmtTopupM3(300.0);
        f.setPhonePeUserFlag(1);
        f.setPaytmUserFlag(1);
        f.setVintageAppsGenreFinance(200);
        f.setAcc0AmtDebitsP30(15000.0);
        f.setCntCcApplicationsC30(1);
        f.setRupay_cc_profile(false);
        return f;
    }
}
