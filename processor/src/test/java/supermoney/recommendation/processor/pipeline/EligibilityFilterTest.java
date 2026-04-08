package supermoney.recommendation.processor.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.UserFeatures;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EligibilityFilterTest {

    private EligibilityFilter filter;

    @BeforeEach
    void setUp() { filter = new EligibilityFilter(); }

    // ── UPI Activation ────────────────────────────────────────────────────────

    @Test
    void upiActivation_dormantWithAccount_eligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(0);
        f.setAcc0AccNumber("ACC123456");
        assertTrue(isEligible(f, Product.UPI_ACTIVATION));
    }

    @Test
    void upiActivation_activeRecently_notEligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(1);
        f.setUpiRecency(10);  // active 10 days ago
        f.setAcc0AccNumber("ACC123456");
        assertFalse(isEligible(f, Product.UPI_ACTIVATION));
    }

    @Test
    void upiActivation_dormantButNoAccount_notEligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(0);
        f.setAcc0AccNumber(null);
        assertFalse(isEligible(f, Product.UPI_ACTIVATION));
    }

    @Test
    void upiActivation_dormantByRecency_eligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(1);
        f.setUpiRecency(95);  // inactive > 90 days
        f.setAcc0AccNumber("ACC123456");
        assertTrue(isEligible(f, Product.UPI_ACTIVATION));
    }

    // ── Personal Loan ─────────────────────────────────────────────────────────

    @Test
    void personalLoan_goodProfile_eligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCalculatedIncomeAmountV4(50000.0);
        f.setCntDelinquncyLoanC90(0);
        f.setDtiRatio(0.30);
        f.setCntActiveLoanAccountsM1(1);
        assertTrue(isEligible(f, Product.PERSONAL_LOAN));
    }

    @Test
    void personalLoan_lowIncome_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCalculatedIncomeAmountV4(15000.0);  // below 20k
        f.setCntDelinquncyLoanC90(0);
        f.setDtiRatio(0.20);
        assertFalse(isEligible(f, Product.PERSONAL_LOAN));
    }

    @Test
    void personalLoan_hasDelinquency_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCalculatedIncomeAmountV4(60000.0);
        f.setCntDelinquncyLoanC90(1);  // any delinquency = fail
        f.setDtiRatio(0.20);
        assertFalse(isEligible(f, Product.PERSONAL_LOAN));
    }

    @Test
    void personalLoan_tooManyActiveLoans_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCalculatedIncomeAmountV4(60000.0);
        f.setCntDelinquncyLoanC90(0);
        f.setDtiRatio(0.20);
        f.setCntActiveLoanAccountsM1(3);  // max 3, so 3 = fail
        assertFalse(isEligible(f, Product.PERSONAL_LOAN));
    }

    @Test
    void personalLoan_dtiTooHigh_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCalculatedIncomeAmountV4(60000.0);
        f.setCntDelinquncyLoanC90(0);
        f.setDtiRatio(0.55);  // above 0.50 threshold
        assertFalse(isEligible(f, Product.PERSONAL_LOAN));
    }

    @Test
    void personalLoan_stressedUser_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.STRESSED);
        f.setCalculatedIncomeAmountV4(80000.0);
        f.setCntDelinquncyLoanC90(0);
        f.setDtiRatio(0.20);
        assertFalse(isEligible(f, Product.PERSONAL_LOAN));
    }

    // ── Fixed Deposit ─────────────────────────────────────────────────────────

    @Test
    void fixedDeposit_noExistingFd_highBalance_eligible() {
        UserFeatures f = base();
        f.setFdFlag(0);
        f.setCntFdAccounts(0);
        f.setTotalAvgBal30(50000.0);
        assertTrue(isEligible(f, Product.FIXED_DEPOSIT));
    }

    @Test
    void fixedDeposit_alreadyHasFd_notEligible() {
        UserFeatures f = base();
        f.setFdFlag(1);  // already has FD
        f.setCntFdAccounts(1);
        f.setTotalAvgBal30(50000.0);
        assertFalse(isEligible(f, Product.FIXED_DEPOSIT));
    }

    @Test
    void fixedDeposit_lowBalance_notEligible() {
        UserFeatures f = base();
        f.setFdFlag(0);
        f.setCntFdAccounts(0);
        f.setTotalAvgBal30(5000.0);  // below 10k threshold
        assertFalse(isEligible(f, Product.FIXED_DEPOSIT));
    }

    // ── Secured Card ──────────────────────────────────────────────────────────

    @Test
    void securedCard_hasFdNoExistingCard_eligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setFdFlag(1);
        f.setCntCcAcc(0);
        f.setCalculatedIncomeAmountV4(20000.0);
        f.setBounceFlag(0);
        assertTrue(isEligible(f, Product.SECURED_CARD));
    }

    @Test
    void securedCard_noFd_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setFdFlag(0);  // no FD = can't use as collateral
        f.setCntCcAcc(0);
        f.setCalculatedIncomeAmountV4(20000.0);
        f.setBounceFlag(0);
        assertFalse(isEligible(f, Product.SECURED_CARD));
    }

    // ── Bill Payments ─────────────────────────────────────────────────────────

    @Test
    void billPayments_hasBillAndUpi_eligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(1);
        f.setBroadbandFlag(1);
        assertTrue(isEligible(f, Product.BILL_PAYMENTS));
    }

    @Test
    void billPayments_noBills_notEligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(1);
        f.setBroadbandFlag(0);
        f.setDthFlag(0);
        f.setElectricityFlag(0);
        f.setPostpaidFlag(0);
        f.setLpgFlag(0);
        assertFalse(isEligible(f, Product.BILL_PAYMENTS));
    }

    // ── Recharges ─────────────────────────────────────────────────────────────

    @Test
    void recharges_prepaidAndUpi_eligible() {
        UserFeatures f = base();
        f.setPrepaidFlag(1);
        f.setUpiUserFlag(1);
        assertTrue(isEligible(f, Product.RECHARGES));
    }

    @Test
    void recharges_notPrepaid_notEligible() {
        UserFeatures f = base();
        f.setPrepaidFlag(0);
        f.setUpiUserFlag(1);
        assertFalse(isEligible(f, Product.RECHARGES));
    }

    // ── Flights ───────────────────────────────────────────────────────────────

    @Test
    void flights_highIncomeHighSavviness_eligible() {
        UserFeatures f = base();
        f.setCalculatedIncomeAmountV4(80000.0);
        f.setDigitalSavviness(8);
        assertTrue(isEligible(f, Product.FLIGHTS));
    }

    @Test
    void flights_lowDigitalSavviness_notEligible() {
        UserFeatures f = base();
        f.setCalculatedIncomeAmountV4(80000.0);
        f.setDigitalSavviness(4);  // below 6 threshold
        assertFalse(isEligible(f, Product.FLIGHTS));
    }

    // ── Unsecured Card ────────────────────────────────────────────────────────

    @Test
    void unsecuredCard_goodProfile_eligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCalculatedIncomeAmountV4(50000.0);
        f.setCntDelinquncyCcC90(0);
        f.setCntDelinquncyLoanC90(0);
        f.setMaxDpdAcc1(0);
        f.setCntCcAcc(0);
        assertTrue(isEligible(f, Product.UNSECURED_CARD));
    }

    @Test
    void unsecuredCard_lowIncome_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCalculatedIncomeAmountV4(20000.0);  // below 25k threshold
        f.setCntDelinquncyCcC90(0);
        f.setCntDelinquncyLoanC90(0);
        f.setMaxDpdAcc1(0);
        f.setCntCcAcc(0);
        assertFalse(isEligible(f, Product.UNSECURED_CARD));
    }

    @Test
    void unsecuredCard_hasNonRupayCard_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCalculatedIncomeAmountV4(50000.0);
        f.setCntCcAcc(1);
        f.setRupay_cc_profile(false);  // already has a non-RuPay unsecured card
        assertFalse(isEligible(f, Product.UNSECURED_CARD));
    }

    @Test
    void unsecuredCard_hasRupayCard_eligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCalculatedIncomeAmountV4(50000.0);
        f.setCntCcAcc(1);
        f.setRupay_cc_profile(true);   // RuPay → upgrade path eligible
        f.setCountActiveCcCards(1);
        f.setCntDelinquncyCcC90(0);
        f.setCntDelinquncyLoanC90(0);
        f.setMaxDpdAcc1(0);
        assertTrue(isEligible(f, Product.UNSECURED_CARD));
    }

    @Test
    void unsecuredCard_maxDpdTooHigh_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCalculatedIncomeAmountV4(50000.0);
        f.setCntCcAcc(0);
        f.setMaxDpdAcc1(30);  // at threshold of 30 = fail (must be < 30)
        assertFalse(isEligible(f, Product.UNSECURED_CARD));
    }

    // ── CC Bill Payment ───────────────────────────────────────────────────────

    @Test
    void ccBillPayment_hasCcAndOutstandingBill_eligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCreditCardUserFlag(1);
        f.setCntCcAcc(1);
        f.setCcBillM0(5000.0);
        assertTrue(isEligible(f, Product.CC_BILL_PAYMENT));
    }

    @Test
    void ccBillPayment_noBill_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCreditCardUserFlag(1);
        f.setCntCcAcc(1);
        f.setCcBillM0(0.0);  // no outstanding bill
        assertFalse(isEligible(f, Product.CC_BILL_PAYMENT));
    }

    @Test
    void ccBillPayment_noCard_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCreditCardUserFlag(0);
        f.setCntCcAcc(0);
        f.setCcBillM0(5000.0);
        assertFalse(isEligible(f, Product.CC_BILL_PAYMENT));
    }

    @Test
    void ccBillPayment_stressedUser_notEligible() {
        UserFeatures f = base();
        f.setHealthTier(HealthTier.STRESSED);
        f.setCreditCardUserFlag(1);
        f.setCntCcAcc(1);
        f.setCcBillM0(5000.0);
        assertFalse(isEligible(f, Product.CC_BILL_PAYMENT));
    }

    // ── Rent Payment ──────────────────────────────────────────────────────────

    @Test
    void rentPayment_upiActiveWithLargeDebit_eligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(1);
        f.setCalculatedIncomeAmountV4(30000.0);
        f.setLargeRecurringDebit(true);
        assertTrue(isEligible(f, Product.RENT_PAYMENT));
    }

    @Test
    void rentPayment_noLargeRecurringDebit_notEligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(1);
        f.setCalculatedIncomeAmountV4(30000.0);
        f.setLargeRecurringDebit(false);  // no rent pattern detected
        assertFalse(isEligible(f, Product.RENT_PAYMENT));
    }

    @Test
    void rentPayment_lowIncome_notEligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(1);
        f.setCalculatedIncomeAmountV4(10000.0);  // below 15k threshold
        f.setLargeRecurringDebit(true);
        assertFalse(isEligible(f, Product.RENT_PAYMENT));
    }

    @Test
    void rentPayment_noUpi_notEligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(0);
        f.setCalculatedIncomeAmountV4(30000.0);
        f.setLargeRecurringDebit(true);
        assertFalse(isEligible(f, Product.RENT_PAYMENT));
    }

    // ── Referrals ─────────────────────────────────────────────────────────────

    @Test
    void referrals_upiActiveHighSavviness_eligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(1);
        f.setDigitalSavviness(7);
        assertTrue(isEligible(f, Product.REFERRALS));
    }

    @Test
    void referrals_lowDigitalSavviness_notEligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(1);
        f.setDigitalSavviness(4);  // below 5 threshold
        assertFalse(isEligible(f, Product.REFERRALS));
    }

    @Test
    void referrals_noUpi_notEligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(0);
        f.setDigitalSavviness(8);
        assertFalse(isEligible(f, Product.REFERRALS));
    }

    @Test
    void referrals_digitalSavvinessExactly5_eligible() {
        UserFeatures f = base();
        f.setUpiUserFlag(1);
        f.setDigitalSavviness(5);  // exactly at threshold
        assertTrue(isEligible(f, Product.REFERRALS));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isEligible(UserFeatures f, Product p) {
        return filter.filter(f, List.of(p)).contains(p);
    }

    private UserFeatures base() {
        UserFeatures f = new UserFeatures();
        f.setHealthTier(HealthTier.HEALTHY);
        f.setCalculatedIncomeAmountV4(50000.0);
        f.setCntDelinquncyLoanC90(0);
        f.setCntDelinquncyCcC90(0);
        f.setDtiRatio(0.20);
        f.setCntActiveLoanAccountsM1(0);
        f.setFdFlag(0);
        f.setCntFdAccounts(0);
        f.setTotalAvgBal30(20000.0);
        f.setUpiUserFlag(1);
        f.setUpiRecency(5);
        f.setAcc0AccNumber("ACC123");
        f.setBounceFlag(0);
        f.setCntCcAcc(0);
        return f;
    }
}
