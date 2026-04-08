package supermoney.recommendation.processor.flink.function;

import org.apache.flink.api.common.functions.MapFunction;
import supermoney.recommendation.common.model.UserFeatures;

/**
 * Parses one CSV line from sms_insights_sample.csv into a UserFeatures POJO.
 *
 * Column order must match COLUMNS exactly. The job filters out the header line
 * before this mapper runs.
 *
 * fis_affordability_v1 note: real Finbox data uses a raw scale (observed 0–~1000).
 * This mapper normalizes it to [0, 1] by dividing by 1000 before storing.
 */
public class CsvToUserFeaturesMapper implements MapFunction<String, UserFeatures> {

    // ── Column order — must match the CSV header exactly ─────────────────────
    // Indices are derived from position in this array.
    static final String[] COLUMNS = {
        "customer_id",
        "fis_affordability_v1",
        "total_income_m0",
        "total_emi_loan_all_acc_m0",
        "bounce_flag",
        "auto_debit_bounce_m0",
        "cnt_delinquncy_loan_c90",
        "cnt_delinquncy_cc_c90",
        "calculated_income_amount_v4",
        "calculated_income_confidence_v4",
        "salary_m1_v4",
        "salary_m2_v4",
        "salary_m3_v4",
        "salary_date_m1_v3",
        "cnt_active_loan_accounts_m1",
        "cnt_loan_applications_c30",
        "loan_acc1",
        "max_dpd_acc1",
        "cnt_cc_acc",
        "count_active_cc_cards",
        "rupay_cc_profile",
        "cnt_cc_applications_c30",
        "cc_bill_m0",
        "cc_bill_latest_date",
        "credit_card_user_flag",
        "cc_utilisation",
        "fd_flag",
        "cnt_fd_accounts",
        "amt_fd_accounts_c180",
        "total_avg_bal_30",
        "acc0_acc_number",
        "acc0_amt_debits_p30",
        "acc0_amt_debits_c30",
        "acc0_amt_debits_c60",
        "acc0_vintage",
        "upi_user_flag",
        "upi_recency",
        "cnt_total_txn_upi_3m",
        "amt_total_txn_upi_3m",
        "amt_total_debits_upi_3m",
        "broadband_flag",
        "dth_flag",
        "electricity_flag",
        "postpaid_flag",
        "lpg_flag",
        "prepaid_flag",
        "max_dpd_broadband",
        "max_dpd_dth",
        "max_dpd_electric",
        "cnt_postpaid_bill_overdue_c180",
        "broadband_recency",
        "dth_recency",
        "electricity_recency",
        "lpg_recency",
        "avg_postpaid_bill_3m",
        "cnt_paytm_wallet_recharge_m1",
        "cnt_paytm_wallet_recharge_m2",
        "cnt_paytm_wallet_recharge_m3",
        "sum_amt_topup_m1",
        "sum_amt_topup_m2",
        "sum_amt_topup_m3",
        "digital_savviness",
        "phonepe_user_flag",
        "paytm_user_flag",
        "mobikwik_user_flag",
        "vintage_apps_genre_finance",
        "recency_apps_genre_finance",
        "net_banking_flag",
        "fps_investment_v1_probability"
    };

    // ── Column index lookup (built once from COLUMNS array) ───────────────────
    private static final java.util.Map<String, Integer> IDX;
    static {
        IDX = new java.util.HashMap<>();
        for (int i = 0; i < COLUMNS.length; i++) IDX.put(COLUMNS[i], i);
    }

    @Override
    public UserFeatures map(String line) {
        String[] v = line.split(",", -1);
        UserFeatures f = new UserFeatures();

        f.setCustomerId(str(v, "customer_id"));

        // fis_affordability_v1: normalize from Finbox raw scale (0–1000) → 0–1
        Double rawFis = dbl(v, "fis_affordability_v1");
        f.setFisAffordabilityV1(rawFis != null ? rawFis / 1000.0 : null);

        f.setTotalIncomeM0(dbl(v, "total_income_m0"));
        f.setTotalEmiLoanAllAccM0(dbl(v, "total_emi_loan_all_acc_m0"));
        f.setBounceFlag(integer(v, "bounce_flag"));
        f.setAutoDebitBounceM0(integer(v, "auto_debit_bounce_m0"));
        f.setCntDelinquncyLoanC90(integer(v, "cnt_delinquncy_loan_c90"));
        f.setCntDelinquncyCcC90(integer(v, "cnt_delinquncy_cc_c90"));

        f.setCalculatedIncomeAmountV4(dbl(v, "calculated_income_amount_v4"));
        f.setCalculatedIncomeConfidenceV4(str(v, "calculated_income_confidence_v4"));
        f.setSalaryM1V4(dbl(v, "salary_m1_v4"));
        f.setSalaryM2V4(dbl(v, "salary_m2_v4"));
        f.setSalaryM3V4(dbl(v, "salary_m3_v4"));
        f.setSalaryDateM1V3(str(v, "salary_date_m1_v3"));

        f.setCntActiveLoanAccountsM1(integer(v, "cnt_active_loan_accounts_m1"));
        f.setCntLoanApplicationsC30(integer(v, "cnt_loan_applications_c30"));
        f.setLoanAcc1(str(v, "loan_acc1"));
        f.setMaxDpdAcc1(integer(v, "max_dpd_acc1"));

        f.setCntCcAcc(integer(v, "cnt_cc_acc"));
        f.setCountActiveCcCards(integer(v, "count_active_cc_cards"));
        f.setRupay_cc_profile(bool(v, "rupay_cc_profile"));
        f.setCntCcApplicationsC30(integer(v, "cnt_cc_applications_c30"));
        f.setCcBillM0(dbl(v, "cc_bill_m0"));
        f.setCcBillLatestDate(str(v, "cc_bill_latest_date"));
        f.setCreditCardUserFlag(integer(v, "credit_card_user_flag"));
        f.setCcUtilisation(dbl(v, "cc_utilisation"));

        f.setFdFlag(integer(v, "fd_flag"));
        f.setCntFdAccounts(integer(v, "cnt_fd_accounts"));
        f.setAmtFdAccountsC180(dbl(v, "amt_fd_accounts_c180"));
        f.setTotalAvgBal30(dbl(v, "total_avg_bal_30"));

        f.setAcc0AccNumber(str(v, "acc0_acc_number"));
        f.setAcc0AmtDebitsP30(dbl(v, "acc0_amt_debits_p30"));
        f.setAcc0AmtDebitsC30(dbl(v, "acc0_amt_debits_c30"));
        f.setAcc0AmtDebitsC60(dbl(v, "acc0_amt_debits_c60"));
        f.setAcc0Vintage(integer(v, "acc0_vintage"));

        f.setUpiUserFlag(integer(v, "upi_user_flag"));
        f.setUpiRecency(integer(v, "upi_recency"));
        f.setCntTotalTxnUpi3m(integer(v, "cnt_total_txn_upi_3m"));
        f.setAmtTotalTxnUpi3m(dbl(v, "amt_total_txn_upi_3m"));
        f.setAmtTotalDebitsUpi3m(dbl(v, "amt_total_debits_upi_3m"));

        f.setBroadbandFlag(integer(v, "broadband_flag"));
        f.setDthFlag(integer(v, "dth_flag"));
        f.setElectricityFlag(integer(v, "electricity_flag"));
        f.setPostpaidFlag(integer(v, "postpaid_flag"));
        f.setLpgFlag(integer(v, "lpg_flag"));
        f.setPrepaidFlag(integer(v, "prepaid_flag"));
        f.setMaxDpdBroadband(integer(v, "max_dpd_broadband"));
        f.setMaxDpdDth(integer(v, "max_dpd_dth"));
        f.setMaxDpdElectric(integer(v, "max_dpd_electric"));
        f.setCntPostpaidBillOverdueC180(integer(v, "cnt_postpaid_bill_overdue_c180"));
        f.setBroadbandRecency(integer(v, "broadband_recency"));
        f.setDthRecency(integer(v, "dth_recency"));
        f.setElectricityRecency(integer(v, "electricity_recency"));
        f.setLpgRecency(integer(v, "lpg_recency"));
        f.setAvgPostpaidBill3m(dbl(v, "avg_postpaid_bill_3m"));

        f.setCntPaytmWalletRechargeM1(integer(v, "cnt_paytm_wallet_recharge_m1"));
        f.setCntPaytmWalletRechargeM2(integer(v, "cnt_paytm_wallet_recharge_m2"));
        f.setCntPaytmWalletRechargeM3(integer(v, "cnt_paytm_wallet_recharge_m3"));
        f.setSumAmtTopupM1(dbl(v, "sum_amt_topup_m1"));
        f.setSumAmtTopupM2(dbl(v, "sum_amt_topup_m2"));
        f.setSumAmtTopupM3(dbl(v, "sum_amt_topup_m3"));

        f.setDigitalSavviness(integer(v, "digital_savviness"));
        f.setPhonePeUserFlag(integer(v, "phonepe_user_flag"));
        f.setPaytmUserFlag(integer(v, "paytm_user_flag"));
        f.setMobikwikUserFlag(integer(v, "mobikwik_user_flag"));
        f.setVintageAppsGenreFinance(integer(v, "vintage_apps_genre_finance"));
        f.setRecencyAppsGenreFinance(integer(v, "recency_apps_genre_finance"));
        f.setNetBankingFlag(integer(v, "net_banking_flag"));
        f.setFpsInvestmentV1Probability(dbl(v, "fps_investment_v1_probability"));

        return f;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String str(String[] v, String col) {
        int idx = IDX.get(col);
        if (idx >= v.length) return null;
        String s = v[idx].trim();
        return s.isEmpty() ? null : s;
    }

    private Double dbl(String[] v, String col) {
        String s = str(v, col);
        if (s == null) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private Integer integer(String[] v, String col) {
        String s = str(v, col);
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private Boolean bool(String[] v, String col) {
        String s = str(v, col);
        if (s == null) return null;
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }
}
