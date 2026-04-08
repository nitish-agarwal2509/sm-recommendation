package supermoney.recommendation.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * All SMS insights columns used in the recommendation pipeline,
 * plus 5 derived secondary features computed by the Flink job.
 *
 * Field names are camelCase Java equivalents of the snake_case BigQuery column names.
 * All fields are nullable (boxed types) to distinguish "absent" from "zero".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserFeatures {

    // ── Identity ──────────────────────────────────────────────────────────────
    private String customerId;         // customer_id (row key)

    // ── Primary bank account (acc0) ───────────────────────────────────────────
    private String acc0AccNumber;      // acc0_acc_number — linkable account (non-null = has account)
    private Double acc0AmtDebitsP30;   // acc0_amt_debits_p30 — debits in prior 30 days
    private Double acc0AmtDebitsC30;   // acc0_amt_debits_c30 — debits in current 30 days
    private Double acc0AmtDebitsC60;   // acc0_amt_debits_c60 — debits in current 60 days
    private Integer acc0Vintage;       // acc0_vintage — account age in days

    // ── Financial health inputs ───────────────────────────────────────────────
    private Double fisAffordabilityV1; // fis_affordability_v1 — Finbox affordability score (0–1)
    private Double totalIncomeM0;      // total_income_m0 — income in current month
    private Double totalEmiLoanAllAccM0; // total_emi_loan_all_acc_m0 — total EMI obligations
    private Integer bounceFlag;        // bounce_flag — 1 if any bounce in recent history
    private Integer autoDebitBounceM0; // auto_debit_bounce_m0 — auto-debit bounces this month
    private Integer cntDelinquncyLoanC90; // cnt_delinquncy_loan_c90 — loan delinquencies in 90 days
    private Integer cntDelinquncyCcC90;   // cnt_delinquncy_cc_c90 — CC delinquencies in 90 days

    // ── Income & employment ───────────────────────────────────────────────────
    private Double calculatedIncomeAmountV4;    // calculated_income_amount_v4
    private String calculatedIncomeConfidenceV4; // calculated_income_confidence_v4 — "HIGH"/"MEDIUM"/"LOW"
    private Double salaryM1V4;                  // salary_m1_v4
    private Double salaryM2V4;                  // salary_m2_v4
    private Double salaryM3V4;                  // salary_m3_v4
    private String salaryDateM1V3;              // salary_date_m1_v3 — e.g. "2026-04-01"

    // ── Loans ─────────────────────────────────────────────────────────────────
    private Integer cntActiveLoanAccountsM1;    // cnt_active_loan_accounts_m1
    private Integer cntLoanApplicationsC30;     // cnt_loan_applications_c30
    private String loanAcc1;                    // loan_acc1 — non-null = has an active loan
    private Integer maxDpdAcc1;                 // max_dpd_acc1 — max days past due on primary account

    // ── Credit cards ──────────────────────────────────────────────────────────
    private Integer cntCcAcc;                   // cnt_cc_acc — number of CC accounts
    private Integer countActiveCcCards;         // count_active_cc_cards
    private Boolean rupay_cc_profile;           // rupay_cc_profile — true if existing card is RuPay
    private Integer cntCcApplicationsC30;       // cnt_cc_applications_c30
    private Double ccBillM0;                    // cc_bill_m0 — outstanding CC bill this month
    private String ccBillLatestDate;            // cc_bill_latest_date — ISO date of latest CC bill
    private Integer creditCardUserFlag;         // credit_card_user_flag — 1 if has a credit card
    private Double ccUtilisation;              // cc_utilisation — current utilisation ratio (0–1)

    // ── Fixed Deposit ──────────────────────────────────────────────────────────
    private Integer fdFlag;                    // fd_flag — 1 if has an FD
    private Integer cntFdAccounts;             // cnt_fd_accounts
    private Double amtFdAccountsC180;          // amt_fd_accounts_c180 — FD amount in last 180 days
    private Double totalAvgBal30;              // total_avg_bal_30 — 30-day avg balance

    // ── UPI ───────────────────────────────────────────────────────────────────
    private Integer upiUserFlag;               // upi_user_flag — 1 if UPI active on super.money
    private Integer upiRecency;                // upi_recency — days since last UPI txn
    private Integer cntTotalTxnUpi3m;          // cnt_total_txn_upi_3m
    private Double amtTotalTxnUpi3m;           // amt_total_txn_upi_3m
    private Double amtTotalDebitsUpi3m;        // amt_total_debits_upi_3m

    // ── Bill Payments ─────────────────────────────────────────────────────────
    private Integer broadbandFlag;             // broadband_flag
    private Integer dthFlag;                   // dth_flag
    private Integer electricityFlag;           // electricity_flag
    private Integer postpaidFlag;              // postpaid_flag
    private Integer lpgFlag;                   // lpg_flag
    private Integer prepaidFlag;               // prepaid_flag
    private Integer maxDpdBroadband;           // max_dpd_broadband
    private Integer maxDpdDth;                 // max_dpd_dth
    private Integer maxDpdElectric;            // max_dpd_electric
    private Integer cntPostpaidBillOverdueC180; // cnt_postpaid_bill_overdue_c180
    private Integer broadbandRecency;          // broadband_recency — days since last bill
    private Integer dthRecency;                // dth_recency
    private Integer electricityRecency;        // electricity_recency
    private Integer lpgRecency;                // lpg_recency
    private Double avgPostpaidBill3m;          // avg_postpaid_bill_3m

    // ── Recharges ─────────────────────────────────────────────────────────────
    private Integer cntPaytmWalletRechargeM1;  // cnt_paytm_wallet_recharge_m1
    private Integer cntPaytmWalletRechargeM2;  // cnt_paytm_wallet_recharge_m2
    private Integer cntPaytmWalletRechargeM3;  // cnt_paytm_wallet_recharge_m3
    private Double sumAmtTopupM1;              // sum_amt_topup_m1
    private Double sumAmtTopupM2;              // sum_amt_topup_m2
    private Double sumAmtTopupM3;              // sum_amt_topup_m3

    // ── Referrals & digital behaviour ────────────────────────────────────────
    private Integer digitalSavviness;          // digital_savviness — 0–10 score
    private Integer phonePeUserFlag;           // phonepe_user_flag
    private Integer paytmUserFlag;             // paytm_user_flag
    private Integer mobikwikUserFlag;          // mobikwik_user_flag
    private Integer vintageAppsGenreFinance;   // vintage_apps_genre_finance — days
    private Integer recencyAppsGenreFinance;   // recency_apps_genre_finance — days since last use
    private Integer netBankingFlag;            // net_banking_flag

    // ── FD / Investment ───────────────────────────────────────────────────────
    private Double fpsInvestmentV1Probability; // fps_investment_v1_probability

    // ─────────────────────────────────────────────────────────────────────────
    // Derived fields — computed by FeatureDeriver in the Flink job
    // (null until FeatureDeriver.derive() is called)
    // ─────────────────────────────────────────────────────────────────────────
    private Double healthScore;                // 0–100 composite financial health score
    private HealthTier healthTier;             // HEALTHY / NEUTRAL / STRESSED
    private Double surplusCash;               // total_income_m0 - total_emi - obligations
    private Double dtiRatio;                  // total_emi / max(total_income, 1)
    private Double avgUpiTicket;              // amt_total_txn_upi_3m / max(cnt_total_txn_upi_3m, 1)
    private Double billUrgency;               // max overdue days across bill types
    private Boolean largeRecurringDebit;      // true if consistent large monthly debit (rent proxy)

    // ── Getters and Setters ───────────────────────────────────────────────────

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getAcc0AccNumber() { return acc0AccNumber; }
    public void setAcc0AccNumber(String acc0AccNumber) { this.acc0AccNumber = acc0AccNumber; }

    public Double getAcc0AmtDebitsP30() { return acc0AmtDebitsP30; }
    public void setAcc0AmtDebitsP30(Double acc0AmtDebitsP30) { this.acc0AmtDebitsP30 = acc0AmtDebitsP30; }

    public Double getAcc0AmtDebitsC30() { return acc0AmtDebitsC30; }
    public void setAcc0AmtDebitsC30(Double acc0AmtDebitsC30) { this.acc0AmtDebitsC30 = acc0AmtDebitsC30; }

    public Double getAcc0AmtDebitsC60() { return acc0AmtDebitsC60; }
    public void setAcc0AmtDebitsC60(Double acc0AmtDebitsC60) { this.acc0AmtDebitsC60 = acc0AmtDebitsC60; }

    public Integer getAcc0Vintage() { return acc0Vintage; }
    public void setAcc0Vintage(Integer acc0Vintage) { this.acc0Vintage = acc0Vintage; }

    public Double getFisAffordabilityV1() { return fisAffordabilityV1; }
    public void setFisAffordabilityV1(Double fisAffordabilityV1) { this.fisAffordabilityV1 = fisAffordabilityV1; }

    public Double getTotalIncomeM0() { return totalIncomeM0; }
    public void setTotalIncomeM0(Double totalIncomeM0) { this.totalIncomeM0 = totalIncomeM0; }

    public Double getTotalEmiLoanAllAccM0() { return totalEmiLoanAllAccM0; }
    public void setTotalEmiLoanAllAccM0(Double totalEmiLoanAllAccM0) { this.totalEmiLoanAllAccM0 = totalEmiLoanAllAccM0; }

    public Integer getBounceFlag() { return bounceFlag; }
    public void setBounceFlag(Integer bounceFlag) { this.bounceFlag = bounceFlag; }

    public Integer getAutoDebitBounceM0() { return autoDebitBounceM0; }
    public void setAutoDebitBounceM0(Integer autoDebitBounceM0) { this.autoDebitBounceM0 = autoDebitBounceM0; }

    public Integer getCntDelinquncyLoanC90() { return cntDelinquncyLoanC90; }
    public void setCntDelinquncyLoanC90(Integer cntDelinquncyLoanC90) { this.cntDelinquncyLoanC90 = cntDelinquncyLoanC90; }

    public Integer getCntDelinquncyCcC90() { return cntDelinquncyCcC90; }
    public void setCntDelinquncyCcC90(Integer cntDelinquncyCcC90) { this.cntDelinquncyCcC90 = cntDelinquncyCcC90; }

    public Double getCalculatedIncomeAmountV4() { return calculatedIncomeAmountV4; }
    public void setCalculatedIncomeAmountV4(Double calculatedIncomeAmountV4) { this.calculatedIncomeAmountV4 = calculatedIncomeAmountV4; }

    public String getCalculatedIncomeConfidenceV4() { return calculatedIncomeConfidenceV4; }
    public void setCalculatedIncomeConfidenceV4(String calculatedIncomeConfidenceV4) { this.calculatedIncomeConfidenceV4 = calculatedIncomeConfidenceV4; }

    public Double getSalaryM1V4() { return salaryM1V4; }
    public void setSalaryM1V4(Double salaryM1V4) { this.salaryM1V4 = salaryM1V4; }

    public Double getSalaryM2V4() { return salaryM2V4; }
    public void setSalaryM2V4(Double salaryM2V4) { this.salaryM2V4 = salaryM2V4; }

    public Double getSalaryM3V4() { return salaryM3V4; }
    public void setSalaryM3V4(Double salaryM3V4) { this.salaryM3V4 = salaryM3V4; }

    public String getSalaryDateM1V3() { return salaryDateM1V3; }
    public void setSalaryDateM1V3(String salaryDateM1V3) { this.salaryDateM1V3 = salaryDateM1V3; }

    public Integer getCntActiveLoanAccountsM1() { return cntActiveLoanAccountsM1; }
    public void setCntActiveLoanAccountsM1(Integer cntActiveLoanAccountsM1) { this.cntActiveLoanAccountsM1 = cntActiveLoanAccountsM1; }

    public Integer getCntLoanApplicationsC30() { return cntLoanApplicationsC30; }
    public void setCntLoanApplicationsC30(Integer cntLoanApplicationsC30) { this.cntLoanApplicationsC30 = cntLoanApplicationsC30; }

    public String getLoanAcc1() { return loanAcc1; }
    public void setLoanAcc1(String loanAcc1) { this.loanAcc1 = loanAcc1; }

    public Integer getMaxDpdAcc1() { return maxDpdAcc1; }
    public void setMaxDpdAcc1(Integer maxDpdAcc1) { this.maxDpdAcc1 = maxDpdAcc1; }

    public Integer getCntCcAcc() { return cntCcAcc; }
    public void setCntCcAcc(Integer cntCcAcc) { this.cntCcAcc = cntCcAcc; }

    public Integer getCountActiveCcCards() { return countActiveCcCards; }
    public void setCountActiveCcCards(Integer countActiveCcCards) { this.countActiveCcCards = countActiveCcCards; }

    public Boolean getRupay_cc_profile() { return rupay_cc_profile; }
    public void setRupay_cc_profile(Boolean rupay_cc_profile) { this.rupay_cc_profile = rupay_cc_profile; }

    public Integer getCntCcApplicationsC30() { return cntCcApplicationsC30; }
    public void setCntCcApplicationsC30(Integer cntCcApplicationsC30) { this.cntCcApplicationsC30 = cntCcApplicationsC30; }

    public Double getCcBillM0() { return ccBillM0; }
    public void setCcBillM0(Double ccBillM0) { this.ccBillM0 = ccBillM0; }

    public String getCcBillLatestDate() { return ccBillLatestDate; }
    public void setCcBillLatestDate(String ccBillLatestDate) { this.ccBillLatestDate = ccBillLatestDate; }

    public Integer getCreditCardUserFlag() { return creditCardUserFlag; }
    public void setCreditCardUserFlag(Integer creditCardUserFlag) { this.creditCardUserFlag = creditCardUserFlag; }

    public Double getCcUtilisation() { return ccUtilisation; }
    public void setCcUtilisation(Double ccUtilisation) { this.ccUtilisation = ccUtilisation; }

    public Integer getFdFlag() { return fdFlag; }
    public void setFdFlag(Integer fdFlag) { this.fdFlag = fdFlag; }

    public Integer getCntFdAccounts() { return cntFdAccounts; }
    public void setCntFdAccounts(Integer cntFdAccounts) { this.cntFdAccounts = cntFdAccounts; }

    public Double getAmtFdAccountsC180() { return amtFdAccountsC180; }
    public void setAmtFdAccountsC180(Double amtFdAccountsC180) { this.amtFdAccountsC180 = amtFdAccountsC180; }

    public Double getTotalAvgBal30() { return totalAvgBal30; }
    public void setTotalAvgBal30(Double totalAvgBal30) { this.totalAvgBal30 = totalAvgBal30; }

    public Integer getUpiUserFlag() { return upiUserFlag; }
    public void setUpiUserFlag(Integer upiUserFlag) { this.upiUserFlag = upiUserFlag; }

    public Integer getUpiRecency() { return upiRecency; }
    public void setUpiRecency(Integer upiRecency) { this.upiRecency = upiRecency; }

    public Integer getCntTotalTxnUpi3m() { return cntTotalTxnUpi3m; }
    public void setCntTotalTxnUpi3m(Integer cntTotalTxnUpi3m) { this.cntTotalTxnUpi3m = cntTotalTxnUpi3m; }

    public Double getAmtTotalTxnUpi3m() { return amtTotalTxnUpi3m; }
    public void setAmtTotalTxnUpi3m(Double amtTotalTxnUpi3m) { this.amtTotalTxnUpi3m = amtTotalTxnUpi3m; }

    public Double getAmtTotalDebitsUpi3m() { return amtTotalDebitsUpi3m; }
    public void setAmtTotalDebitsUpi3m(Double amtTotalDebitsUpi3m) { this.amtTotalDebitsUpi3m = amtTotalDebitsUpi3m; }

    public Integer getBroadbandFlag() { return broadbandFlag; }
    public void setBroadbandFlag(Integer broadbandFlag) { this.broadbandFlag = broadbandFlag; }

    public Integer getDthFlag() { return dthFlag; }
    public void setDthFlag(Integer dthFlag) { this.dthFlag = dthFlag; }

    public Integer getElectricityFlag() { return electricityFlag; }
    public void setElectricityFlag(Integer electricityFlag) { this.electricityFlag = electricityFlag; }

    public Integer getPostpaidFlag() { return postpaidFlag; }
    public void setPostpaidFlag(Integer postpaidFlag) { this.postpaidFlag = postpaidFlag; }

    public Integer getLpgFlag() { return lpgFlag; }
    public void setLpgFlag(Integer lpgFlag) { this.lpgFlag = lpgFlag; }

    public Integer getPrepaidFlag() { return prepaidFlag; }
    public void setPrepaidFlag(Integer prepaidFlag) { this.prepaidFlag = prepaidFlag; }

    public Integer getMaxDpdBroadband() { return maxDpdBroadband; }
    public void setMaxDpdBroadband(Integer maxDpdBroadband) { this.maxDpdBroadband = maxDpdBroadband; }

    public Integer getMaxDpdDth() { return maxDpdDth; }
    public void setMaxDpdDth(Integer maxDpdDth) { this.maxDpdDth = maxDpdDth; }

    public Integer getMaxDpdElectric() { return maxDpdElectric; }
    public void setMaxDpdElectric(Integer maxDpdElectric) { this.maxDpdElectric = maxDpdElectric; }

    public Integer getCntPostpaidBillOverdueC180() { return cntPostpaidBillOverdueC180; }
    public void setCntPostpaidBillOverdueC180(Integer cntPostpaidBillOverdueC180) { this.cntPostpaidBillOverdueC180 = cntPostpaidBillOverdueC180; }

    public Integer getBroadbandRecency() { return broadbandRecency; }
    public void setBroadbandRecency(Integer broadbandRecency) { this.broadbandRecency = broadbandRecency; }

    public Integer getDthRecency() { return dthRecency; }
    public void setDthRecency(Integer dthRecency) { this.dthRecency = dthRecency; }

    public Integer getElectricityRecency() { return electricityRecency; }
    public void setElectricityRecency(Integer electricityRecency) { this.electricityRecency = electricityRecency; }

    public Integer getLpgRecency() { return lpgRecency; }
    public void setLpgRecency(Integer lpgRecency) { this.lpgRecency = lpgRecency; }

    public Double getAvgPostpaidBill3m() { return avgPostpaidBill3m; }
    public void setAvgPostpaidBill3m(Double avgPostpaidBill3m) { this.avgPostpaidBill3m = avgPostpaidBill3m; }

    public Integer getCntPaytmWalletRechargeM1() { return cntPaytmWalletRechargeM1; }
    public void setCntPaytmWalletRechargeM1(Integer cntPaytmWalletRechargeM1) { this.cntPaytmWalletRechargeM1 = cntPaytmWalletRechargeM1; }

    public Integer getCntPaytmWalletRechargeM2() { return cntPaytmWalletRechargeM2; }
    public void setCntPaytmWalletRechargeM2(Integer cntPaytmWalletRechargeM2) { this.cntPaytmWalletRechargeM2 = cntPaytmWalletRechargeM2; }

    public Integer getCntPaytmWalletRechargeM3() { return cntPaytmWalletRechargeM3; }
    public void setCntPaytmWalletRechargeM3(Integer cntPaytmWalletRechargeM3) { this.cntPaytmWalletRechargeM3 = cntPaytmWalletRechargeM3; }

    public Double getSumAmtTopupM1() { return sumAmtTopupM1; }
    public void setSumAmtTopupM1(Double sumAmtTopupM1) { this.sumAmtTopupM1 = sumAmtTopupM1; }

    public Double getSumAmtTopupM2() { return sumAmtTopupM2; }
    public void setSumAmtTopupM2(Double sumAmtTopupM2) { this.sumAmtTopupM2 = sumAmtTopupM2; }

    public Double getSumAmtTopupM3() { return sumAmtTopupM3; }
    public void setSumAmtTopupM3(Double sumAmtTopupM3) { this.sumAmtTopupM3 = sumAmtTopupM3; }

    public Integer getDigitalSavviness() { return digitalSavviness; }
    public void setDigitalSavviness(Integer digitalSavviness) { this.digitalSavviness = digitalSavviness; }

    public Integer getPhonePeUserFlag() { return phonePeUserFlag; }
    public void setPhonePeUserFlag(Integer phonePeUserFlag) { this.phonePeUserFlag = phonePeUserFlag; }

    public Integer getPaytmUserFlag() { return paytmUserFlag; }
    public void setPaytmUserFlag(Integer paytmUserFlag) { this.paytmUserFlag = paytmUserFlag; }

    public Integer getMobikwikUserFlag() { return mobikwikUserFlag; }
    public void setMobikwikUserFlag(Integer mobikwikUserFlag) { this.mobikwikUserFlag = mobikwikUserFlag; }

    public Integer getVintageAppsGenreFinance() { return vintageAppsGenreFinance; }
    public void setVintageAppsGenreFinance(Integer vintageAppsGenreFinance) { this.vintageAppsGenreFinance = vintageAppsGenreFinance; }

    public Integer getRecencyAppsGenreFinance() { return recencyAppsGenreFinance; }
    public void setRecencyAppsGenreFinance(Integer recencyAppsGenreFinance) { this.recencyAppsGenreFinance = recencyAppsGenreFinance; }

    public Integer getNetBankingFlag() { return netBankingFlag; }
    public void setNetBankingFlag(Integer netBankingFlag) { this.netBankingFlag = netBankingFlag; }

    public Double getFpsInvestmentV1Probability() { return fpsInvestmentV1Probability; }
    public void setFpsInvestmentV1Probability(Double fpsInvestmentV1Probability) { this.fpsInvestmentV1Probability = fpsInvestmentV1Probability; }

    // ── Derived field getters/setters ─────────────────────────────────────────

    public Double getHealthScore() { return healthScore; }
    public void setHealthScore(Double healthScore) { this.healthScore = healthScore; }

    public HealthTier getHealthTier() { return healthTier; }
    public void setHealthTier(HealthTier healthTier) { this.healthTier = healthTier; }

    public Double getSurplusCash() { return surplusCash; }
    public void setSurplusCash(Double surplusCash) { this.surplusCash = surplusCash; }

    public Double getDtiRatio() { return dtiRatio; }
    public void setDtiRatio(Double dtiRatio) { this.dtiRatio = dtiRatio; }

    public Double getAvgUpiTicket() { return avgUpiTicket; }
    public void setAvgUpiTicket(Double avgUpiTicket) { this.avgUpiTicket = avgUpiTicket; }

    public Double getBillUrgency() { return billUrgency; }
    public void setBillUrgency(Double billUrgency) { this.billUrgency = billUrgency; }

    public Boolean getLargeRecurringDebit() { return largeRecurringDebit; }
    public void setLargeRecurringDebit(Boolean largeRecurringDebit) { this.largeRecurringDebit = largeRecurringDebit; }
}
