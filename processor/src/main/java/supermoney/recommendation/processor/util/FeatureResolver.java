package supermoney.recommendation.processor.util;

import supermoney.recommendation.common.model.UserFeatures;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves signal column names (from scoring_rules.yaml) to numeric values from UserFeatures.
 *
 * Regular fields map directly to UserFeatures getters.
 * Special columns (binary expressions, derived aggregates) are resolved explicitly.
 *
 * Returns null if the column is unknown or the underlying field is null/unavailable.
 */
public class FeatureResolver {

    private static final Map<String, Function<UserFeatures, Double>> RESOLVERS = new HashMap<>();

    static {
        // ── Direct field mappings ──────────────────────────────────────────────
        reg("calculatedIncomeAmountV4",   f -> f.getCalculatedIncomeAmountV4());
        reg("digitalSavviness",           f -> toDouble(f.getDigitalSavviness()));
        reg("netBankingFlag",             f -> toDouble(f.getNetBankingFlag()));
        reg("recencyAppsGenreFinance",    f -> toDouble(f.getRecencyAppsGenreFinance()));
        reg("cntLoanApplicationsC30",     f -> toDouble(f.getCntLoanApplicationsC30()));
        reg("dtiRatio",                   f -> f.getDtiRatio());
        reg("acc0Vintage",                f -> toDouble(f.getAcc0Vintage()));
        reg("cntCcApplicationsC30",       f -> toDouble(f.getCntCcApplicationsC30()));
        reg("amtTotalDebitsUpi3m",        f -> f.getAmtTotalDebitsUpi3m());
        reg("amtFdAccountsC180",          f -> f.getAmtFdAccountsC180());
        reg("ccBillM0",                   f -> f.getCcBillM0());
        reg("upiUserFlag",                f -> toDouble(f.getUpiUserFlag()));
        reg("ccUtilisation",              f -> f.getCcUtilisation());
        reg("acc0AmtDebitsP30",           f -> f.getAcc0AmtDebitsP30());
        reg("billUrgency",                f -> f.getBillUrgency());
        reg("avgPostpaidBill3m",          f -> f.getAvgPostpaidBill3m());
        reg("cntTotalTxnUpi3m",           f -> toDouble(f.getCntTotalTxnUpi3m()));
        reg("vintageAppsGenreFinance",    f -> toDouble(f.getVintageAppsGenreFinance()));
        reg("totalAvgBal30",              f -> f.getTotalAvgBal30());
        reg("surplusCash",                f -> f.getSurplusCash());
        reg("fpsInvestmentV1Probability", f -> f.getFpsInvestmentV1Probability());

        // ── Binary: non-null field → 1.0, null → 0.0 ─────────────────────────
        reg("acc0AccNumber",              f -> f.getAcc0AccNumber() != null ? 1.0 : 0.0);

        // ── Binary expressions (combined conditions → 1.0 or 0.0) ─────────────
        // Personal Loan: cnt_delinquncy_loan_c90=0 AND auto_debit_bounce_m0=0
        reg("binary_clean_credit", f -> {
            int loanDelinq = orZero(f.getCntDelinquncyLoanC90());
            int bounce = orZero(f.getAutoDebitBounceM0());
            return (loanDelinq == 0 && bounce == 0) ? 1.0 : 0.0;
        });

        // Unsecured Card: max_dpd_acc1=0 AND cnt_delinquncy_cc_c90=0
        reg("binary_clean_cc_credit", f -> {
            int dpd = orZero(f.getMaxDpdAcc1());
            int ccDelinq = orZero(f.getCntDelinquncyCcC90());
            return (dpd == 0 && ccDelinq == 0) ? 1.0 : 0.0;
        });

        // Secured Card: bounce_flag=0 AND cnt_delinquncy_loan_c90=0
        reg("binary_clean_secured", f -> {
            int bounce = orZero(f.getBounceFlag());
            int loanDelinq = orZero(f.getCntDelinquncyLoanC90());
            return (bounce == 0 && loanDelinq == 0) ? 1.0 : 0.0;
        });

        // Salary 3-month consistency: salary present in m1, m2, m3
        reg("binary_salary_3m", f -> {
            double s1 = orZeroD(f.getSalaryM1V4());
            double s2 = orZeroD(f.getSalaryM2V4());
            double s3 = orZeroD(f.getSalaryM3V4());
            return (s1 > 0 && s2 > 0 && s3 > 0) ? 1.0 : 0.5;
        });

        // ── Special signals: non-standard binary (true=1.0, false=0.5) ────────
        // RuPay upgrade opportunity: true=1.0 (upgrade path), false=0.5 (may still benefit)
        reg("rupay_cc_profile", f -> Boolean.TRUE.equals(f.getRupay_cc_profile()) ? 1.0 : 0.5);

        // Income confidence: HIGH=1.0, else 0.5
        reg("calculatedIncomeConfidenceV4", f ->
            "HIGH".equalsIgnoreCase(f.getCalculatedIncomeConfidenceV4()) ? 1.0 : 0.5);

        // ── Computed aggregates ───────────────────────────────────────────────
        // CC Bill urgency: days since latest CC bill date (higher = more overdue)
        reg("ccBillLatestDate", f -> {
            String dateStr = f.getCcBillLatestDate();
            if (dateStr == null || dateStr.isBlank()) return null;
            try {
                LocalDate billDate = LocalDate.parse(dateStr.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
                return (double) ChronoUnit.DAYS.between(billDate, LocalDate.now());
            } catch (Exception e) {
                return null;
            }
        });

        // Recharge frequency: sum of paytm wallet recharge counts m1+m2+m3
        reg("rechargeFrequency3m", f ->
            (double)(orZero(f.getCntPaytmWalletRechargeM1())
                   + orZero(f.getCntPaytmWalletRechargeM2())
                   + orZero(f.getCntPaytmWalletRechargeM3()))
        );

        // Average recharge amount: (sum_amt_topup m1+m2+m3) / 3
        reg("avgRechargeAmount3m", f -> {
            double total = orZeroD(f.getSumAmtTopupM1())
                         + orZeroD(f.getSumAmtTopupM2())
                         + orZeroD(f.getSumAmtTopupM3());
            return total / 3.0;
        });

        // Active bill types: count of flags set to 1
        reg("activeBillTypesCount", f ->
            (double)(flag(f.getBroadbandFlag()) + flag(f.getDthFlag()) + flag(f.getElectricityFlag())
                   + flag(f.getPostpaidFlag()) + flag(f.getLpgFlag()))
        );

        // Multi-platform usage: count of apps used
        reg("multiPlatformUsage", f ->
            (double)(flag(f.getPhonePeUserFlag()) + flag(f.getPaytmUserFlag()) + flag(f.getMobikwikUserFlag()))
        );

        // CC bill application intent: cnt_loan_applications_c30 + cnt_cc_applications_c30
        reg("cntLoanApplicationsC30_plus_cc", f ->
            (double)(orZero(f.getCntLoanApplicationsC30()) + orZero(f.getCntCcApplicationsC30()))
        );
    }

    /** Resolve a signal column to its numeric value for a given user's features. */
    public static Double resolve(String column, UserFeatures features) {
        Function<UserFeatures, Double> resolver = RESOLVERS.get(column);
        if (resolver == null) return null;
        return resolver.apply(features);
    }

    private static void reg(String column, Function<UserFeatures, Double> fn) {
        RESOLVERS.put(column, fn);
    }

    private static double toDouble(Integer v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    private static double orZeroD(Double v) {
        return v == null ? 0.0 : v;
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    private static int flag(Integer v) {
        return (v != null && v == 1) ? 1 : 0;
    }
}
