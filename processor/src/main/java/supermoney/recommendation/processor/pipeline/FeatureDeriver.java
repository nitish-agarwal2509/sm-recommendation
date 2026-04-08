package supermoney.recommendation.processor.pipeline;

import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.UserFeatures;

/**
 * Stage 0: Derives 5 secondary features from raw SMS insights fields.
 * Mutates and returns the same UserFeatures object (in-place derivation).
 *
 * Must be called before any other pipeline stage.
 */
public class FeatureDeriver {

    /**
     * Derives all secondary features and sets them on the UserFeatures object.
     * Safe to call multiple times (idempotent — overwrites derived fields).
     */
    public UserFeatures derive(UserFeatures f) {
        deriveHealthScore(f);
        deriveSurplusCash(f);
        deriveDtiRatio(f);
        deriveAvgUpiTicket(f);
        deriveBillUrgency(f);
        deriveLargeRecurringDebit(f);
        return f;
    }

    // ── 1. Financial Health Score (0–100) ─────────────────────────────────────

    private void deriveHealthScore(UserFeatures f) {
        double fisBase   = orZero(f.getFisAffordabilityV1()) * 100.0;
        double loanPen   = Math.min(orZeroI(f.getCntDelinquncyLoanC90()), 3) / 3.0 * 15.0;
        double ccPen     = Math.min(orZeroI(f.getCntDelinquncyCcC90()), 3) / 3.0 * 10.0;
        double bouncePen = (orZeroI(f.getBounceFlag()) == 1 ? 10.0 : 0.0)
                         + (orZeroI(f.getAutoDebitBounceM0()) > 0 ? 5.0 : 0.0);
        double dti       = orZero(f.getTotalEmiLoanAllAccM0())
                         / Math.max(orZero(f.getTotalIncomeM0()), 1.0);
        double dtiPen    = clamp((dti - 0.30) * 50.0, 0.0, 30.0);

        double healthScore = clamp(fisBase - loanPen - ccPen - bouncePen - dtiPen, 0.0, 100.0);
        f.setHealthScore(healthScore);

        if (healthScore >= 70.0)      f.setHealthTier(HealthTier.HEALTHY);
        else if (healthScore >= 40.0) f.setHealthTier(HealthTier.NEUTRAL);
        else                          f.setHealthTier(HealthTier.STRESSED);
    }

    // ── 2. Monthly Surplus Cash ────────────────────────────────────────────────

    private void deriveSurplusCash(UserFeatures f) {
        double income = orZero(f.getTotalIncomeM0());
        double emi    = orZero(f.getTotalEmiLoanAllAccM0());
        f.setSurplusCash(income - emi);
    }

    // ── 3. Debt-to-Income Ratio ────────────────────────────────────────────────

    private void deriveDtiRatio(UserFeatures f) {
        double emi    = orZero(f.getTotalEmiLoanAllAccM0());
        double income = Math.max(orZero(f.getTotalIncomeM0()), 1.0);
        f.setDtiRatio(emi / income);
    }

    // ── 4. Average UPI Ticket Size ─────────────────────────────────────────────

    private void deriveAvgUpiTicket(UserFeatures f) {
        double amt = orZero(f.getAmtTotalTxnUpi3m());
        int    cnt = Math.max(orZeroI(f.getCntTotalTxnUpi3m()), 1);
        f.setAvgUpiTicket(amt / cnt);
    }

    // ── 5. Bill Urgency Score ──────────────────────────────────────────────────

    private void deriveBillUrgency(UserFeatures f) {
        double broadband  = orZeroI(f.getMaxDpdBroadband());
        double dth        = orZeroI(f.getMaxDpdDth());
        double electric   = orZeroI(f.getMaxDpdElectric());
        double postpaid   = orZeroI(f.getCntPostpaidBillOverdueC180()) > 0 ? 30.0 : 0.0;
        f.setBillUrgency(Math.max(Math.max(broadband, dth), Math.max(electric, postpaid)));
    }

    // ── 6. Large Recurring Debit (rent proxy) ─────────────────────────────────

    private void deriveLargeRecurringDebit(UserFeatures f) {
        Double p30 = f.getAcc0AmtDebitsP30();
        Double c30 = f.getAcc0AmtDebitsC30();
        Double c60 = f.getAcc0AmtDebitsC60();
        Integer vintage = f.getAcc0Vintage();

        boolean large = p30 != null && p30 >= 5000.0
                     && c30 != null && c30 >= 5000.0
                     && c60 != null && c60 >= 10000.0  // 2 months consistent
                     && vintage != null && vintage >= 60;
        f.setLargeRecurringDebit(large);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double orZero(Double v) {
        return v == null ? 0.0 : v;
    }

    private int orZeroI(Integer v) {
        return v == null ? 0 : v;
    }
}
