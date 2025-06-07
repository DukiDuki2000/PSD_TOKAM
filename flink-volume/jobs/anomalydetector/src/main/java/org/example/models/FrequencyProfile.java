package org.example.models;

public class FrequencyProfile {
    public String cardId;
    public double avgTransactionCount;  // Average number of transactions per window
    public double stdTransactionCount;  // Standard deviation of transaction count
    public double avgSmallThreshold;    // Threshold for what's considered a "small" transaction
    public double avgSmallTransactionRatio;  // Average ratio of small transactions
    public double stdSmallTransactionRatio;  // Standard deviation of small transaction ratio

    public FrequencyProfile() {}

    public FrequencyProfile(String cardId, double avgTransactionCount, double stdTransactionCount,
                            double avgSmallThreshold, double avgSmallTransactionRatio,
                            double stdSmallTransactionRatio) {
        this.cardId = cardId;
        this.avgTransactionCount = avgTransactionCount;
        this.stdTransactionCount = stdTransactionCount;
        this.avgSmallThreshold = avgSmallThreshold;
        this.avgSmallTransactionRatio = avgSmallTransactionRatio;
        this.stdSmallTransactionRatio = stdSmallTransactionRatio;
    }
}