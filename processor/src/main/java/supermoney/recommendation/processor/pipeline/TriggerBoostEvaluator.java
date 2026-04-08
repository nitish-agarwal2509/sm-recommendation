package supermoney.recommendation.processor.pipeline;

import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.UserFeatures;
import supermoney.recommendation.processor.util.FeatureResolver;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 6: Trigger Boost Evaluator.
 * Applies batch-approximated trigger boosts to eligible products.
 * All boost amounts and conditions are derived from the PRD.
 *
 * Trigger boosts reward contextual signals that increase urgency/intent
 * for a specific product at the time of the batch run.
 */
public class TriggerBoostEvaluator {

    private final LocalDate batchDate;

    /** Use the current date as the batch run date. */
    public TriggerBoostEvaluator() {
        this(LocalDate.now());
    }

    /** Inject a specific batch date (useful for testing). */
    public TriggerBoostEvaluator(LocalDate batchDate) {
        this.batchDate = batchDate;
    }

    /**
     * Evaluates trigger boost for each eligible product.
     *
     * @param features          user's features
     * @param eligibleProducts  products that passed health gate + eligibility filter
     * @return map of Product → trigger boost amount (0.0 if no boost applies)
     */
    public Map<Product, Double> evaluate(UserFeatures features, List<Product> eligibleProducts) {
        Map<Product, Double> boosts = new HashMap<>();
        for (Product p : eligibleProducts) {
            boosts.put(p, 0.0);
        }

        // ── 1. Salary within 3 days of batch run → FD, Rent, CC Bill +15 ──────
        if (isSalaryDueSoon(features)) {
            addBoost(boosts, Product.FIXED_DEPOSIT, 15.0);
            addBoost(boosts, Product.RENT_PAYMENT, 15.0);
            addBoost(boosts, Product.CC_BILL_PAYMENT, 15.0);
        }

        // ── 2. Low balance → Personal Loan +12 ────────────────────────────────
        Double avgBal = features.getTotalAvgBal30();
        if (avgBal != null && avgBal < 5000) {
            addBoost(boosts, Product.PERSONAL_LOAN, 12.0);
        }

        // ── 3. Bill urgency high → Bill Payments +20 ─────────────────────────
        Double billUrgency = features.getBillUrgency();
        if (billUrgency != null && billUrgency >= 30) {
            addBoost(boosts, Product.BILL_PAYMENTS, 20.0);
        }

        // ── 4. CC bill due in >= 23 days → CC Bill Payment +20 ───────────────
        if (isCcBillDueSoon(features)) {
            addBoost(boosts, Product.CC_BILL_PAYMENT, 20.0);
        }

        // ── 5. Recent loan or CC application → Personal Loan + Unsecured Card +10
        int loanApps = orZero(features.getCntLoanApplicationsC30());
        int ccApps   = orZero(features.getCntCcApplicationsC30());
        if (loanApps > 0 || ccApps > 0) {
            addBoost(boosts, Product.PERSONAL_LOAN, 10.0);
            addBoost(boosts, Product.UNSECURED_CARD, 10.0);
        }

        // ── 6. CC bill outstanding → Personal Loan cross-sell +15 ─────────────
        if (isCcBillDueSoon(features) && orZeroD(features.getCcBillM0()) > 0) {
            addBoost(boosts, Product.PERSONAL_LOAN, 15.0);
        }

        return boosts;
    }

    /** salary_date_m1_v3 is within 3 days of the batch run date */
    private boolean isSalaryDueSoon(UserFeatures f) {
        String salaryDateStr = f.getSalaryDateM1V3();
        if (salaryDateStr == null || salaryDateStr.isBlank()) return false;
        try {
            LocalDate salaryDate = LocalDate.parse(salaryDateStr.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
            long daysDiff = Math.abs(ChronoUnit.DAYS.between(batchDate, salaryDate));
            return daysDiff <= 3;
        } catch (Exception e) {
            return false;
        }
    }

    /** cc_bill_m0 > 0 AND days since cc_bill_latest_date >= 23 */
    private boolean isCcBillDueSoon(UserFeatures f) {
        if (orZeroD(f.getCcBillM0()) <= 0) return false;
        Double daysSince = FeatureResolver.resolve("ccBillLatestDate", f);
        return daysSince != null && daysSince >= 23;
    }

    private void addBoost(Map<Product, Double> boosts, Product product, double amount) {
        if (boosts.containsKey(product)) {
            boosts.merge(product, amount, Double::sum);
        }
        // If product is not in boosts map it's not eligible — skip silently
    }

    private int orZero(Integer v)    { return v == null ? 0 : v; }
    private double orZeroD(Double v) { return v == null ? 0.0 : v; }
}
