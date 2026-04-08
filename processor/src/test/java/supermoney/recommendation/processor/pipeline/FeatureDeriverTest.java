package supermoney.recommendation.processor.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.UserFeatures;

import static org.junit.jupiter.api.Assertions.*;

class FeatureDeriverTest {

    private FeatureDeriver deriver;

    @BeforeEach
    void setUp() { deriver = new FeatureDeriver(); }

    @Test
    void healthyUserScoresAbove70() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(1.0);      // max affordability → 60 pts
        f.setCntDelinquncyLoanC90(0);
        f.setCntDelinquncyCcC90(0);
        f.setBounceFlag(0);
        f.setAutoDebitBounceM0(0);
        f.setTotalIncomeM0(100000.0);
        f.setTotalEmiLoanAllAccM0(10000.0); // DTI 10% — well below 30% threshold

        deriver.derive(f);

        assertNotNull(f.getHealthScore());
        assertTrue(f.getHealthScore() >= 70.0, "Expected HEALTHY score >= 70, got: " + f.getHealthScore());
        assertEquals(HealthTier.HEALTHY, f.getHealthTier());
    }

    @Test
    void stressedUserScoresBelow40() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(0.3);       // low affordability → 18 pts
        f.setCntDelinquncyLoanC90(3);       // max loan penalty = 15 pts
        f.setCntDelinquncyCcC90(3);         // max CC penalty = 10 pts
        f.setBounceFlag(1);                 // bounce penalty = 10 pts
        f.setAutoDebitBounceM0(1);          // +5 pts
        f.setTotalIncomeM0(20000.0);
        f.setTotalEmiLoanAllAccM0(15000.0); // DTI 75% — well above 30%, DTI penalty = 22.5

        deriver.derive(f);

        assertTrue(f.getHealthScore() < 40.0, "Expected STRESSED score < 40, got: " + f.getHealthScore());
        assertEquals(HealthTier.STRESSED, f.getHealthTier());
    }

    @Test
    void neutralUserScoresBetween40And70() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(0.7);       // 42 pts from fis
        f.setCntDelinquncyLoanC90(0);
        f.setCntDelinquncyCcC90(0);
        f.setBounceFlag(0);
        f.setAutoDebitBounceM0(0);
        f.setTotalIncomeM0(50000.0);
        f.setTotalEmiLoanAllAccM0(20000.0); // DTI 40% → (0.40-0.30)*50=5 pts penalty

        deriver.derive(f);

        double score = f.getHealthScore();
        assertTrue(score >= 40.0 && score < 70.0,
            "Expected NEUTRAL score 40–69, got: " + score);
        assertEquals(HealthTier.NEUTRAL, f.getHealthTier());
    }

    @Test
    void healthScoreClampsTo0_100() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(0.0);       // 0 pts
        f.setCntDelinquncyLoanC90(10);      // capped at 3 → full 15 penalty
        f.setCntDelinquncyCcC90(10);        // capped at 3 → full 10 penalty
        f.setBounceFlag(1);
        f.setAutoDebitBounceM0(1);
        f.setTotalIncomeM0(1000.0);
        f.setTotalEmiLoanAllAccM0(1000.0);  // DTI = 1 → max DTI penalty 30

        deriver.derive(f);

        assertEquals(0.0, f.getHealthScore(), 0.001);
    }

    @Test
    void surplusCashIsIncomeMinusEmi() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(1.0);
        f.setTotalIncomeM0(60000.0);
        f.setTotalEmiLoanAllAccM0(15000.0);

        deriver.derive(f);

        assertEquals(45000.0, f.getSurplusCash(), 0.001);
    }

    @Test
    void dtiRatioComputedCorrectly() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(1.0);
        f.setTotalIncomeM0(80000.0);
        f.setTotalEmiLoanAllAccM0(32000.0);

        deriver.derive(f);

        assertEquals(0.40, f.getDtiRatio(), 0.001);
    }

    @Test
    void avgUpiTicketComputedCorrectly() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(1.0);
        f.setAmtTotalTxnUpi3m(90000.0);
        f.setCntTotalTxnUpi3m(30);

        deriver.derive(f);

        assertEquals(3000.0, f.getAvgUpiTicket(), 0.001);
    }

    @Test
    void avgUpiTicketHandlesZeroCount() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(1.0);
        f.setAmtTotalTxnUpi3m(0.0);
        f.setCntTotalTxnUpi3m(0);

        deriver.derive(f);

        assertEquals(0.0, f.getAvgUpiTicket(), 0.001); // 0 / max(0,1) = 0
    }

    @Test
    void billUrgencyTakesMaxAcrossTypes() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(1.0);
        f.setMaxDpdBroadband(15);
        f.setMaxDpdDth(30);
        f.setMaxDpdElectric(10);
        f.setCntPostpaidBillOverdueC180(0);

        deriver.derive(f);

        assertEquals(30.0, f.getBillUrgency(), 0.001);
    }

    @Test
    void billUrgencyAdds30ForPostpaidOverdue() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(1.0);
        f.setMaxDpdBroadband(0);
        f.setMaxDpdDth(0);
        f.setMaxDpdElectric(0);
        f.setCntPostpaidBillOverdueC180(2); // overdue → adds 30

        deriver.derive(f);

        assertEquals(30.0, f.getBillUrgency(), 0.001);
    }

    @Test
    void largeRecurringDebitDetectedCorrectly() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(1.0);
        f.setAcc0AmtDebitsP30(10000.0);
        f.setAcc0AmtDebitsC30(10000.0);
        f.setAcc0AmtDebitsC60(20000.0);
        f.setAcc0Vintage(90);

        deriver.derive(f);

        assertTrue(f.getLargeRecurringDebit(), "Should detect large recurring debit");
    }

    @Test
    void largeRecurringDebitNotDetectedWhenTooLow() {
        UserFeatures f = new UserFeatures();
        f.setFisAffordabilityV1(1.0);
        f.setAcc0AmtDebitsP30(2000.0);  // below 5000 threshold

        deriver.derive(f);

        assertFalse(f.getLargeRecurringDebit(), "Should not detect large recurring debit");
    }

    @Test
    void nullFieldsHandledSafely() {
        UserFeatures f = new UserFeatures(); // all fields null

        assertDoesNotThrow(() -> deriver.derive(f));
        assertNotNull(f.getHealthScore());
        assertNotNull(f.getHealthTier());
        assertEquals(0.0, f.getHealthScore(), 0.001);
        assertEquals(HealthTier.STRESSED, f.getHealthTier());
    }
}
