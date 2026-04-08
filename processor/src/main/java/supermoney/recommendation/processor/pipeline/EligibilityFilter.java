package supermoney.recommendation.processor.pipeline;

import supermoney.recommendation.common.model.HealthTier;
import supermoney.recommendation.common.model.Product;
import supermoney.recommendation.common.model.UserFeatures;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Stage 3: Eligibility Filter — hard pass/fail rules per product.
 * Also handles held-product exclusion (inferred from SMS insights).
 *
 * A product that fails eligibility is dropped entirely — no score computed.
 */
public class EligibilityFilter {

    /**
     * Returns the subset of candidates that pass all eligibility rules.
     */
    public List<Product> filter(UserFeatures f, List<Product> candidates) {
        return candidates.stream()
            .filter(p -> isEligible(f, p))
            .collect(Collectors.toList());
    }

    private boolean isEligible(UserFeatures f, Product product) {
        return switch (product) {
            case UPI_ACTIVATION   -> eligibleForUpiActivation(f);
            case PERSONAL_LOAN    -> eligibleForPersonalLoan(f);
            case UNSECURED_CARD   -> eligibleForUnsecuredCard(f);
            case SECURED_CARD     -> eligibleForSecuredCard(f);
            case CC_BILL_PAYMENT  -> eligibleForCcBillPayment(f);
            case RENT_PAYMENT     -> eligibleForRentPayment(f);
            case BILL_PAYMENTS    -> eligibleForBillPayments(f);
            case RECHARGES        -> eligibleForRecharges(f);
            case REFERRALS        -> eligibleForReferrals(f);
            case FIXED_DEPOSIT    -> eligibleForFixedDeposit(f);
            case FLIGHTS          -> eligibleForFlights(f);
        };
    }

    // ── UPI Activation ────────────────────────────────────────────────────────
    // Eligible if: dormant on super.money AND has a linkable bank account
    // Dormant = upiUserFlag=0 OR upiRecency > 90
    // Held = upiUserFlag=1 AND upiRecency <= 30 (recently active → skip)

    private boolean eligibleForUpiActivation(UserFeatures f) {
        int upiFlag    = orZero(f.getUpiUserFlag());
        int upiRecency = orZero(f.getUpiRecency());
        boolean dormant = (upiFlag == 0) || (upiRecency > 90);
        boolean hasAccount = f.getAcc0AccNumber() != null && !f.getAcc0AccNumber().isBlank();
        return dormant && hasAccount;
    }

    // ── Personal Loan ─────────────────────────────────────────────────────────
    // Requires: income >= 20k, zero delinquency, DTI < 50%, active loans < 3
    // Health gate already blocks STRESSED — health tier check is belt-and-suspenders

    private boolean eligibleForPersonalLoan(UserFeatures f) {
        if (f.getHealthTier() == HealthTier.STRESSED) return false;
        double income    = orZero(f.getCalculatedIncomeAmountV4());
        int delinquency  = orZero(f.getCntDelinquncyLoanC90());
        double dti       = orZeroD(f.getDtiRatio());
        int activeLoans  = orZero(f.getCntActiveLoanAccountsM1());
        return income >= 20000 && delinquency == 0 && dti < 0.50 && activeLoans < 3;
    }

    // ── Unsecured Card ────────────────────────────────────────────────────────
    // Requires: income >= 25k, no CC delinquency, no loan delinquency, max_dpd_acc1 < 30
    // No existing unsecured card (cnt_cc_acc=0 OR existing is RuPay with exactly 1 active card)

    private boolean eligibleForUnsecuredCard(UserFeatures f) {
        if (f.getHealthTier() == HealthTier.STRESSED) return false;
        double income       = orZero(f.getCalculatedIncomeAmountV4());
        int ccDelinquency   = orZero(f.getCntDelinquncyCcC90());
        int loanDelinquency = orZero(f.getCntDelinquncyLoanC90());
        int maxDpd          = orZero(f.getMaxDpdAcc1());
        int cntCcAcc        = orZero(f.getCntCcAcc());
        boolean rupay       = Boolean.TRUE.equals(f.getRupay_cc_profile());
        int activeCcCards   = orZero(f.getCountActiveCcCards());

        if (income < 25000) return false;
        if (ccDelinquency > 0 || loanDelinquency > 0) return false;
        if (maxDpd >= 30) return false;
        // Held check: already has unsecured card = cnt_cc_acc >= 1 AND not RuPay
        // OR rupay with more than 1 active card (already upgraded)
        if (cntCcAcc >= 1 && !rupay) return false;  // has non-RuPay card → already has unsecured
        if (cntCcAcc >= 1 && rupay && activeCcCards > 1) return false; // multiple cards already
        return true;
    }

    // ── Secured Card ──────────────────────────────────────────────────────────
    // Requires: fd_flag=1 (has FD for collateral), no existing CC, income >= 15k, no bounce

    private boolean eligibleForSecuredCard(UserFeatures f) {
        if (f.getHealthTier() == HealthTier.STRESSED) return false;
        int fdFlag   = orZero(f.getFdFlag());
        int cntCcAcc = orZero(f.getCntCcAcc());
        double income = orZero(f.getCalculatedIncomeAmountV4());
        int bounce   = orZero(f.getBounceFlag());

        return fdFlag == 1 && cntCcAcc == 0 && income >= 15000 && bounce == 0;
    }

    // ── CC Bill Payment ───────────────────────────────────────────────────────
    // Requires: has a credit card AND has an outstanding bill

    private boolean eligibleForCcBillPayment(UserFeatures f) {
        if (f.getHealthTier() == HealthTier.STRESSED) return false;
        int ccFlag   = orZero(f.getCreditCardUserFlag());
        int cntCcAcc = orZero(f.getCntCcAcc());
        double bill  = orZeroD(f.getCcBillM0());
        return (ccFlag == 1 || cntCcAcc > 0) && bill > 0;
    }

    // ── Rent Payment ──────────────────────────────────────────────────────────
    // Requires: UPI active, income >= 15k, large recurring debit pattern

    private boolean eligibleForRentPayment(UserFeatures f) {
        int upiFlag  = orZero(f.getUpiUserFlag());
        double income = orZero(f.getCalculatedIncomeAmountV4());
        boolean largeDebit = Boolean.TRUE.equals(f.getLargeRecurringDebit());
        return upiFlag == 1 && income >= 15000 && largeDebit;
    }

    // ── Bill Payments ─────────────────────────────────────────────────────────
    // Requires: at least one bill type active AND UPI active

    private boolean eligibleForBillPayments(UserFeatures f) {
        int upiFlag    = orZero(f.getUpiUserFlag());
        boolean hasBill = orZero(f.getBroadbandFlag()) == 1
                       || orZero(f.getDthFlag()) == 1
                       || orZero(f.getElectricityFlag()) == 1
                       || orZero(f.getPostpaidFlag()) == 1
                       || orZero(f.getLpgFlag()) == 1;
        return upiFlag == 1 && hasBill;
    }

    // ── Recharges ─────────────────────────────────────────────────────────────
    // Requires: prepaid SIM AND UPI active

    private boolean eligibleForRecharges(UserFeatures f) {
        int prepaid = orZero(f.getPrepaidFlag());
        int upiFlag = orZero(f.getUpiUserFlag());
        return prepaid == 1 && upiFlag == 1;
    }

    // ── Referrals ─────────────────────────────────────────────────────────────
    // Requires: UPI active AND digital_savviness >= 5

    private boolean eligibleForReferrals(UserFeatures f) {
        int upiFlag    = orZero(f.getUpiUserFlag());
        int savviness  = orZero(f.getDigitalSavviness());
        return upiFlag == 1 && savviness >= 5;
    }

    // ── Fixed Deposit ─────────────────────────────────────────────────────────
    // Requires: no existing FD, avg balance >= 10k
    // Held check: fd_flag=1 OR cnt_fd_accounts >= 1 → already has FD

    private boolean eligibleForFixedDeposit(UserFeatures f) {
        int fdFlag      = orZero(f.getFdFlag());
        int cntFd       = orZero(f.getCntFdAccounts());
        double avgBal   = orZeroD(f.getTotalAvgBal30());
        return fdFlag == 0 && cntFd == 0 && avgBal >= 10000;
    }

    // ── Flights ───────────────────────────────────────────────────────────────
    // Requires: high income (>= 50k) AND digital_savviness >= 6

    private boolean eligibleForFlights(UserFeatures f) {
        double income  = orZero(f.getCalculatedIncomeAmountV4());
        int savviness  = orZero(f.getDigitalSavviness());
        return income >= 50000 && savviness >= 6;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private double orZero(Double v)   { return v == null ? 0.0 : v; }
    private double orZeroD(Double v)  { return v == null ? 0.0 : v; }
    private int orZero(Integer v)     { return v == null ? 0 : v; }
}
