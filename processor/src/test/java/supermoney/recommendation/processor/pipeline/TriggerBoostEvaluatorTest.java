package supermoney.recommendation.processor.pipeline;

import org.junit.jupiter.api.Test;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.UserFeatures;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TriggerBoostEvaluatorTest {

    // Fixed batch date for deterministic tests
    private static final LocalDate BATCH_DATE = LocalDate.of(2026, 4, 8);

    private TriggerBoostEvaluator evaluator() {
        return new TriggerBoostEvaluator(BATCH_DATE);
    }

    // ── 1. Salary due soon → FD, Rent, CC Bill +15 ───────────────────────────

    @Test
    void salaryDueSoon_boostsFd_Rent_CcBill() {
        UserFeatures f = base();
        f.setSalaryDateM1V3("2026-04-09"); // 1 day from batch date → within 3 days
        f.setCcBillM0(5000.0);
        f.setCcBillLatestDate("2026-03-15"); // 24 days ago → cc bill due
        f.setTotalAvgBal30(50000.0);        // above 5k → no low-balance boost

        List<Product> eligible = List.of(Product.FIXED_DEPOSIT, Product.RENT_PAYMENT,
            Product.CC_BILL_PAYMENT, Product.PERSONAL_LOAN);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(15.0, boosts.get(Product.FIXED_DEPOSIT), 0.001);
        assertEquals(15.0, boosts.get(Product.RENT_PAYMENT), 0.001);
        // CC_BILL_PAYMENT also gets +20 for cc_bill_due + +15 for salary = +35
        assertTrue(boosts.get(Product.CC_BILL_PAYMENT) >= 15.0);
    }

    @Test
    void salaryNotDueSoon_noBoost() {
        UserFeatures f = base();
        f.setSalaryDateM1V3("2026-03-01"); // 38 days ago → not within 3 days
        f.setTotalAvgBal30(50000.0);

        List<Product> eligible = List.of(Product.FIXED_DEPOSIT, Product.RENT_PAYMENT);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(0.0, boosts.get(Product.FIXED_DEPOSIT), 0.001);
        assertEquals(0.0, boosts.get(Product.RENT_PAYMENT), 0.001);
    }

    @Test
    void salaryDateExactly3DaysAway_stillBoosts() {
        UserFeatures f = base();
        f.setSalaryDateM1V3("2026-04-11"); // exactly 3 days from batch date
        f.setTotalAvgBal30(50000.0);

        List<Product> eligible = List.of(Product.FIXED_DEPOSIT);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(15.0, boosts.get(Product.FIXED_DEPOSIT), 0.001);
    }

    // ── 2. Low balance → Personal Loan +12 ────────────────────────────────────

    @Test
    void lowBalance_bootsPersonalLoan() {
        UserFeatures f = base();
        f.setTotalAvgBal30(3000.0); // below 5000 threshold

        List<Product> eligible = List.of(Product.PERSONAL_LOAN, Product.FIXED_DEPOSIT);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(12.0, boosts.get(Product.PERSONAL_LOAN), 0.001);
        assertEquals(0.0, boosts.get(Product.FIXED_DEPOSIT), 0.001);
    }

    @Test
    void highBalance_noPersonalLoanBoost() {
        UserFeatures f = base();
        f.setTotalAvgBal30(20000.0);

        List<Product> eligible = List.of(Product.PERSONAL_LOAN);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(0.0, boosts.get(Product.PERSONAL_LOAN), 0.001);
    }

    // ── 3. High bill urgency → Bill Payments +20 ──────────────────────────────

    @Test
    void highBillUrgency_boostsBillPayments() {
        UserFeatures f = base();
        f.setBillUrgency(35.0); // above 30 threshold

        List<Product> eligible = List.of(Product.BILL_PAYMENTS, Product.REFERRALS);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(20.0, boosts.get(Product.BILL_PAYMENTS), 0.001);
        assertEquals(0.0, boosts.get(Product.REFERRALS), 0.001);
    }

    @Test
    void lowBillUrgency_noBillBoost() {
        UserFeatures f = base();
        f.setBillUrgency(15.0); // below 30 threshold

        List<Product> eligible = List.of(Product.BILL_PAYMENTS);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(0.0, boosts.get(Product.BILL_PAYMENTS), 0.001);
    }

    @Test
    void billUrgencyExactly30_boosts() {
        UserFeatures f = base();
        f.setBillUrgency(30.0); // exactly at threshold

        List<Product> eligible = List.of(Product.BILL_PAYMENTS);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(20.0, boosts.get(Product.BILL_PAYMENTS), 0.001);
    }

    // ── 4. CC bill due >= 23 days → CC Bill Payment +20 ──────────────────────

    @Test
    void ccBillDue_boostsCcBillPayment() {
        UserFeatures f = base();
        f.setCcBillM0(8000.0);
        f.setCcBillLatestDate("2026-03-15"); // 24 days before batch date → >= 23 days

        List<Product> eligible = List.of(Product.CC_BILL_PAYMENT);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(20.0, boosts.get(Product.CC_BILL_PAYMENT), 0.001);
    }

    @Test
    void ccBillNotDue_noCcBillBoost() {
        UserFeatures f = base();
        f.setCcBillM0(8000.0);
        f.setCcBillLatestDate("2026-04-06"); // 2 days ago → not due yet

        List<Product> eligible = List.of(Product.CC_BILL_PAYMENT);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(0.0, boosts.get(Product.CC_BILL_PAYMENT), 0.001);
    }

    @Test
    void ccBillZero_noCcBillBoost() {
        UserFeatures f = base();
        f.setCcBillM0(0.0); // no outstanding bill
        f.setCcBillLatestDate("2026-03-15");

        List<Product> eligible = List.of(Product.CC_BILL_PAYMENT);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(0.0, boosts.get(Product.CC_BILL_PAYMENT), 0.001);
    }

    // ── 5. Recent loan/CC application → Personal Loan + Unsecured Card +10 ───

    @Test
    void recentLoanApplication_boostsLoanAndCard() {
        UserFeatures f = base();
        f.setCntLoanApplicationsC30(2);
        f.setCntCcApplicationsC30(0);
        f.setTotalAvgBal30(50000.0);

        List<Product> eligible = List.of(Product.PERSONAL_LOAN, Product.UNSECURED_CARD,
            Product.FIXED_DEPOSIT);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(10.0, boosts.get(Product.PERSONAL_LOAN), 0.001);
        assertEquals(10.0, boosts.get(Product.UNSECURED_CARD), 0.001);
        assertEquals(0.0, boosts.get(Product.FIXED_DEPOSIT), 0.001);
    }

    @Test
    void recentCcApplication_boostsLoanAndCard() {
        UserFeatures f = base();
        f.setCntLoanApplicationsC30(0);
        f.setCntCcApplicationsC30(1);
        f.setTotalAvgBal30(50000.0);

        List<Product> eligible = List.of(Product.PERSONAL_LOAN, Product.UNSECURED_CARD);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(10.0, boosts.get(Product.PERSONAL_LOAN), 0.001);
        assertEquals(10.0, boosts.get(Product.UNSECURED_CARD), 0.001);
    }

    @Test
    void noRecentApplication_noBoost() {
        UserFeatures f = base();
        f.setCntLoanApplicationsC30(0);
        f.setCntCcApplicationsC30(0);
        f.setTotalAvgBal30(50000.0);

        List<Product> eligible = List.of(Product.PERSONAL_LOAN, Product.UNSECURED_CARD);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertEquals(0.0, boosts.get(Product.PERSONAL_LOAN), 0.001);
        assertEquals(0.0, boosts.get(Product.UNSECURED_CARD), 0.001);
    }

    // ── 6. CC bill → Personal Loan cross-sell +15 ─────────────────────────────

    @Test
    void ccBillDue_crossSellBoostsPersonalLoan() {
        UserFeatures f = base();
        f.setCcBillM0(10000.0);
        f.setCcBillLatestDate("2026-03-14"); // 25 days ago → >= 23 threshold
        f.setTotalAvgBal30(50000.0);

        List<Product> eligible = List.of(Product.PERSONAL_LOAN, Product.CC_BILL_PAYMENT);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        // Personal Loan gets +15 from cross-sell
        assertEquals(15.0, boosts.get(Product.PERSONAL_LOAN), 0.001);
        // CC Bill gets +20 from cc_bill_due boost
        assertEquals(20.0, boosts.get(Product.CC_BILL_PAYMENT), 0.001);
    }

    // ── Boosts not applied to ineligible products ─────────────────────────────

    @Test
    void boostNotAppliedToProductNotInEligibleList() {
        UserFeatures f = base();
        f.setBillUrgency(45.0);

        // BILL_PAYMENTS not in eligible list (filtered out before this stage)
        List<Product> eligible = List.of(Product.REFERRALS);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        assertFalse(boosts.containsKey(Product.BILL_PAYMENTS),
            "Boost should not be applied to product not in eligible list");
    }

    // ── Multiple boosts stack ─────────────────────────────────────────────────

    @Test
    void multipleBoostsStackForSameProduct() {
        UserFeatures f = base();
        f.setCntLoanApplicationsC30(1); // +10 for intent
        f.setTotalAvgBal30(2000.0);    // +12 for low balance
        f.setSalaryDateM1V3("2026-04-08"); // exactly on batch date → +0 (salary boost is for FD/Rent/CcBill)

        List<Product> eligible = List.of(Product.PERSONAL_LOAN);
        Map<Product, Double> boosts = evaluator().evaluate(f, eligible);

        // Personal Loan gets +10 (intent) + +12 (low balance) = +22
        assertEquals(22.0, boosts.get(Product.PERSONAL_LOAN), 0.001);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private UserFeatures base() {
        UserFeatures f = new UserFeatures();
        f.setTotalAvgBal30(50000.0);
        f.setBillUrgency(5.0);
        f.setCcBillM0(0.0);
        f.setCcBillLatestDate(null);
        f.setCntLoanApplicationsC30(0);
        f.setCntCcApplicationsC30(0);
        f.setSalaryDateM1V3(null);
        return f;
    }
}
