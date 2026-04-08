package supermoney.recommendation.common.model;

public enum Surface {
    HOME_BANNER,        // S1 — persistent banner at top of home screen
    HOME_BOTTOMSHEET,   // S2 — blocking modal on app open, higher CTR
    POST_UPI,           // S3 — shown after a UPI payment completes
    CASHBACK_REDEEMED,  // S4 — shown when user redeems cashback
    REWARDS_HISTORY     // S5 — shown on rewards/cashback history page
}
