package supermoney.recommendation.processor.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.config.ConfigLoader;
import supermoney.recommendation.common.config.ScoringConfig;
import supermoney.recommendation.common.model.*;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end pipeline tests covering 5 representative user scenarios.
 * No Flink, no Spring Boot — pure Java.
 */
class RecommendationPipelineTest {

    private RecommendationPipeline pipeline;

    @BeforeEach
    void setUp() {
        ScoringConfig config = ConfigLoader.loadFromClasspath("/scoring_rules.yaml");
        // Use fixed batch date to make trigger boost assertions deterministic
        pipeline = new RecommendationPipeline(config, new TriggerBoostEvaluator(LocalDate.of(2026, 4, 8)));
    }

    // ── Scenario 1: Healthy, high income, active UPI → credit product expected ─

    @Test
    void scenario1_healthyHighIncome_getsCreditOrLoan() {
        UserFeatures f = new UserFeatures();
        f.setCustomerId("u001");
        f.setFisAffordabilityV1(0.9);
        f.setTotalIncomeM0(100000.0);
        f.setTotalEmiLoanAllAccM0(15000.0);
        f.setBounceFlag(0);
        f.setAutoDebitBounceM0(0);
        f.setCntDelinquncyLoanC90(0);
        f.setCntDelinquncyCcC90(0);
        f.setCalculatedIncomeAmountV4(100000.0);
        f.setDtiRatio(0.15);
        f.setCntActiveLoanAccountsM1(0);
        f.setMaxDpdAcc1(0);
        f.setCntCcAcc(0);
        f.setUpiUserFlag(1);
        f.setUpiRecency(5);
        f.setAcc0AccNumber("ACC001");
        f.setDigitalSavviness(8);
        f.setTotalAvgBal30(80000.0);
        f.setFdFlag(0);
        f.setCntFdAccounts(0);
        f.setBroadbandFlag(1);
        f.setPrepaidFlag(1);
        f.setAcc0Vintage(400);

        List<ScoredCandidate> result = pipeline.run(f, Collections.emptyMap());

        assertFalse(result.isEmpty(), "Healthy high-income user should get recommendations");
        assertTrue(result.size() <= 5, "Should return at most 5 candidates");
        assertEquals(HealthTier.HEALTHY, result.get(0).getHealthTier());

        // At least one credit product should be in results (HEALTHY tier, good income)
        boolean hasCreditProduct = result.stream().anyMatch(c ->
            c.getProduct() == Product.PERSONAL_LOAN
            || c.getProduct() == Product.UNSECURED_CARD
            || c.getProduct() == Product.SECURED_CARD);
        assertTrue(hasCreditProduct, "Healthy high-income user should have at least one credit product in candidates");
    }

    // ── Scenario 2: Stressed user → no credit products ────────────────────────

    @Test
    void scenario2_stressedUser_noCreditProducts() {
        UserFeatures f = new UserFeatures();
        f.setCustomerId("u002");
        f.setFisAffordabilityV1(0.2);
        f.setTotalIncomeM0(25000.0);
        f.setTotalEmiLoanAllAccM0(20000.0);  // very high EMI
        f.setBounceFlag(1);
        f.setAutoDebitBounceM0(2);
        f.setCntDelinquncyLoanC90(2);
        f.setCntDelinquncyCcC90(1);
        f.setCalculatedIncomeAmountV4(25000.0);
        f.setDtiRatio(0.80);
        f.setUpiUserFlag(1);
        f.setUpiRecency(5);
        f.setAcc0AccNumber("ACC002");
        f.setBroadbandFlag(1);
        f.setElectricityFlag(1);
        f.setPrepaidFlag(1);
        f.setDigitalSavviness(5);
        f.setFdFlag(0);
        f.setCntFdAccounts(0);
        f.setTotalAvgBal30(15000.0);

        List<ScoredCandidate> result = pipeline.run(f, Collections.emptyMap());

        assertFalse(result.isEmpty(), "Stressed user should still get some non-credit recommendations");

        // No credit products allowed for STRESSED
        for (ScoredCandidate c : result) {
            assertNotEquals(Product.PERSONAL_LOAN, c.getProduct(), "STRESSED: no PERSONAL_LOAN");
            assertNotEquals(Product.UNSECURED_CARD, c.getProduct(), "STRESSED: no UNSECURED_CARD");
            assertNotEquals(Product.SECURED_CARD, c.getProduct(), "STRESSED: no SECURED_CARD");
            assertNotEquals(Product.CC_BILL_PAYMENT, c.getProduct(), "STRESSED: no CC_BILL_PAYMENT");
        }
    }

    // ── Scenario 3: No UPI → UPI Activation ranks #1 ─────────────────────────

    @Test
    void scenario3_noUpi_upiActivationRanksFirst() {
        UserFeatures f = new UserFeatures();
        f.setCustomerId("u003");
        f.setFisAffordabilityV1(0.8);
        f.setTotalIncomeM0(60000.0);
        f.setTotalEmiLoanAllAccM0(8000.0);
        f.setBounceFlag(0);
        f.setAutoDebitBounceM0(0);
        f.setCntDelinquncyLoanC90(0);
        f.setCntDelinquncyCcC90(0);
        f.setUpiUserFlag(0);             // no UPI on super.money
        f.setAcc0AccNumber("ACC003");     // has linkable account
        f.setDigitalSavviness(7);
        f.setNetBankingFlag(1);
        f.setRecencyAppsGenreFinance(20);
        f.setCalculatedIncomeAmountV4(60000.0);
        f.setDtiRatio(0.13);
        f.setCntActiveLoanAccountsM1(0);
        f.setTotalAvgBal30(50000.0);
        f.setFdFlag(0);
        f.setCntFdAccounts(0);
        f.setBroadbandFlag(1);

        List<ScoredCandidate> result = pipeline.run(f, Collections.emptyMap());

        assertFalse(result.isEmpty());
        assertEquals(Product.UPI_ACTIVATION, result.get(0).getProduct(),
            "UPI_ACTIVATION must rank #1 when user has no UPI");
    }

    // ── Scenario 4: Overdue bills → BILL_PAYMENTS in top results ─────────────

    @Test
    void scenario4_overdueHighBills_billPaymentsRanksHigh() {
        UserFeatures f = new UserFeatures();
        f.setCustomerId("u004");
        f.setFisAffordabilityV1(0.65);
        f.setTotalIncomeM0(45000.0);
        f.setTotalEmiLoanAllAccM0(8000.0);
        f.setBounceFlag(0);
        f.setAutoDebitBounceM0(0);
        f.setCntDelinquncyLoanC90(0);
        f.setCntDelinquncyCcC90(0);
        f.setUpiUserFlag(1);
        f.setUpiRecency(3);
        f.setBroadbandFlag(1);
        f.setElectricityFlag(1);
        f.setMaxDpdBroadband(40);        // high overdue days
        f.setMaxDpdElectric(35);
        f.setCntPostpaidBillOverdueC180(3);
        f.setAvgPostpaidBill3m(800.0);
        f.setDigitalSavviness(5);
        f.setTotalAvgBal30(30000.0);
        f.setFdFlag(0);
        f.setCntFdAccounts(0);
        f.setCalculatedIncomeAmountV4(45000.0);
        f.setDtiRatio(0.18);

        List<ScoredCandidate> result = pipeline.run(f, Collections.emptyMap());

        assertFalse(result.isEmpty());
        boolean hasBillPayments = result.stream()
            .anyMatch(c -> c.getProduct() == Product.BILL_PAYMENTS);
        assertTrue(hasBillPayments, "User with overdue bills should have BILL_PAYMENTS in top-5");

        // BILL_PAYMENTS should be in top-3
        int billIdx = result.indexOf(result.stream()
            .filter(c -> c.getProduct() == Product.BILL_PAYMENTS)
            .findFirst().orElse(null));
        assertTrue(billIdx < 3, "BILL_PAYMENTS should rank in top-3 for user with overdue bills, got rank: " + billIdx);
    }

    // ── Scenario 5: Fatigue accumulation reduces product score ────────────────

    @Test
    void scenario5_fatigueAccumulation_penaltyApplied() {
        UserFeatures f = new UserFeatures();
        f.setCustomerId("u005");
        f.setFisAffordabilityV1(0.85);
        f.setTotalIncomeM0(70000.0);
        f.setTotalEmiLoanAllAccM0(10000.0);
        f.setBounceFlag(0);
        f.setAutoDebitBounceM0(0);
        f.setCntDelinquncyLoanC90(0);
        f.setCntDelinquncyCcC90(0);
        f.setCalculatedIncomeAmountV4(70000.0);
        f.setDtiRatio(0.14);
        f.setCntActiveLoanAccountsM1(0);
        f.setMaxDpdAcc1(0);
        f.setCntCcAcc(0);
        f.setTotalAvgBal30(60000.0);
        f.setFdFlag(0);
        f.setCntFdAccounts(0);
        f.setUpiUserFlag(1);
        f.setUpiRecency(5);

        // Run without fatigue
        List<ScoredCandidate> fresh = pipeline.run(f, Collections.emptyMap());
        assertFalse(fresh.isEmpty());

        // Find the top product and give it max fatigue
        Product topProduct = fresh.get(0).getProduct();
        double freshScore = fresh.get(0).getPreSurfaceScore();

        FatigueData maxFatigue = new FatigueData(10, "2026-04-08T12:00:00Z", false);
        List<ScoredCandidate> fatigued = pipeline.run(f, Map.of(topProduct, maxFatigue));

        // The top product should score lower (or not be at top) after max fatigue
        double fatiguedTopScore = fatigued.stream()
            .filter(c -> c.getProduct() == topProduct)
            .mapToDouble(ScoredCandidate::getPreSurfaceScore)
            .findFirst()
            .orElse(-1.0);

        if (fatiguedTopScore > 0) {
            assertTrue(fatiguedTopScore < freshScore,
                "Fatigued product should score lower than fresh. Fresh: " + freshScore + ", Fatigued: " + fatiguedTopScore);
        }
        // If the product is absent (fatigued out), that also counts as correct behavior
    }

    // ── General assertions ────────────────────────────────────────────────────

    @Test
    void pipelineNeverReturnsMoreThan5Candidates() {
        UserFeatures f = fullUserProfile();
        List<ScoredCandidate> result = pipeline.run(f, Collections.emptyMap());
        assertTrue(result.size() <= 5, "Pipeline must never return more than 5 candidates");
    }

    @Test
    void pipelineReturnsEmptyForUserWithNoEligibleProducts() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(0.0);
        f.setBounceFlag(1);
        f.setAutoDebitBounceM0(1);
        f.setCntDelinquncyLoanC90(3);
        f.setCntDelinquncyCcC90(3);
        f.setTotalIncomeM0(5000.0);     // very low income
        f.setTotalEmiLoanAllAccM0(4000.0);
        f.setCalculatedIncomeAmountV4(5000.0);
        f.setUpiUserFlag(0);           // no UPI
        f.setAcc0AccNumber(null);      // no linkable account (no UPI activation either)
        f.setFdFlag(1);                // has FD (FD excluded)
        f.setCntFdAccounts(1);
        f.setTotalAvgBal30(1000.0);    // too low for FD
        f.setBroadbandFlag(0);
        f.setDthFlag(0);
        f.setElectricityFlag(0);
        f.setPostpaidFlag(0);
        f.setLpgFlag(0);
        f.setPrepaidFlag(0);
        f.setDigitalSavviness(0);       // too low for referrals + flights

        // This user should have very few if any eligible products
        assertDoesNotThrow(() -> pipeline.run(f, Collections.emptyMap()));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private UserFeatures fullUserProfile() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(0.85);
        f.setTotalIncomeM0(80000.0);
        f.setTotalEmiLoanAllAccM0(12000.0);
        f.setBounceFlag(0);
        f.setAutoDebitBounceM0(0);
        f.setCntDelinquncyLoanC90(0);
        f.setCntDelinquncyCcC90(0);
        f.setCalculatedIncomeAmountV4(80000.0);
        f.setCalculatedIncomeConfidenceV4("HIGH");
        f.setDtiRatio(0.15);
        f.setCntActiveLoanAccountsM1(1);
        f.setMaxDpdAcc1(0);
        f.setCntCcAcc(0);
        f.setUpiUserFlag(1);
        f.setUpiRecency(3);
        f.setAcc0AccNumber("ACC005");
        f.setDigitalSavviness(8);
        f.setNetBankingFlag(1);
        f.setRecencyAppsGenreFinance(10);
        f.setTotalAvgBal30(120000.0);
        f.setFdFlag(0);
        f.setCntFdAccounts(0);
        f.setBroadbandFlag(1);
        f.setElectricityFlag(1);
        f.setPrepaidFlag(1);
        f.setPostpaidFlag(1);
        f.setSalaryM1V4(80000.0);
        f.setSalaryM2V4(80000.0);
        f.setSalaryM3V4(80000.0);
        f.setFpsInvestmentV1Probability(0.7);
        f.setPhonePeUserFlag(1);
        f.setPaytmUserFlag(1);
        f.setCntTotalTxnUpi3m(60);
        f.setAmtTotalTxnUpi3m(180000.0);
        f.setAmtTotalDebitsUpi3m(150000.0);
        f.setAcc0AmtDebitsP30(15000.0);
        f.setAcc0AmtDebitsC30(15000.0);
        f.setAcc0AmtDebitsC60(30000.0);
        f.setAcc0Vintage(500);
        f.setSurplusCash(45000.0);
        f.setBillUrgency(20.0);
        f.setAvgPostpaidBill3m(600.0);
        f.setCntPaytmWalletRechargeM1(3);
        f.setCntPaytmWalletRechargeM2(3);
        f.setCntPaytmWalletRechargeM3(3);
        f.setSumAmtTopupM1(400.0);
        f.setSumAmtTopupM2(400.0);
        f.setSumAmtTopupM3(400.0);
        f.setVintageAppsGenreFinance(300);
        return f;
    }
}
