package supermoney.recommendation.common.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Hard eligibility thresholds for a product.
 * Any failed threshold drops the product entirely before scoring.
 * Null means "no constraint for this field".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EligibilityConfig {

    private Double minIncome;          // minimum calculated_income_amount_v4
    private Double maxDti;             // maximum dti_ratio
    private Integer maxActiveLoans;    // maximum cnt_active_loan_accounts_m1
    private Integer maxDelinquencyLoan; // max cnt_delinquncy_loan_c90 allowed (usually 0)
    private Integer maxDelinquencyCc;   // max cnt_delinquncy_cc_c90 allowed (usually 0)
    private Integer maxDpdAcc1;        // max max_dpd_acc1 allowed
    private Double minAvgBalance;      // minimum total_avg_bal_30
    private Integer minDigitalSavviness; // minimum digital_savviness
    private Integer minIncome_flights; // minimum income for flights (separate threshold)

    public EligibilityConfig() {}

    public Double getMinIncome() { return minIncome; }
    public void setMinIncome(Double minIncome) { this.minIncome = minIncome; }

    public Double getMaxDti() { return maxDti; }
    public void setMaxDti(Double maxDti) { this.maxDti = maxDti; }

    public Integer getMaxActiveLoans() { return maxActiveLoans; }
    public void setMaxActiveLoans(Integer maxActiveLoans) { this.maxActiveLoans = maxActiveLoans; }

    public Integer getMaxDelinquencyLoan() { return maxDelinquencyLoan; }
    public void setMaxDelinquencyLoan(Integer maxDelinquencyLoan) { this.maxDelinquencyLoan = maxDelinquencyLoan; }

    public Integer getMaxDelinquencyCc() { return maxDelinquencyCc; }
    public void setMaxDelinquencyCc(Integer maxDelinquencyCc) { this.maxDelinquencyCc = maxDelinquencyCc; }

    public Integer getMaxDpdAcc1() { return maxDpdAcc1; }
    public void setMaxDpdAcc1(Integer maxDpdAcc1) { this.maxDpdAcc1 = maxDpdAcc1; }

    public Double getMinAvgBalance() { return minAvgBalance; }
    public void setMinAvgBalance(Double minAvgBalance) { this.minAvgBalance = minAvgBalance; }

    public Integer getMinDigitalSavviness() { return minDigitalSavviness; }
    public void setMinDigitalSavviness(Integer minDigitalSavviness) { this.minDigitalSavviness = minDigitalSavviness; }

    public Integer getMinIncome_flights() { return minIncome_flights; }
    public void setMinIncome_flights(Integer minIncome_flights) { this.minIncome_flights = minIncome_flights; }
}
