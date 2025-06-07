package org.example.models;

public class TransactionStats {
    public String key;
    public long count;
    public double sum;
    public double avg;
    public double min;
    public double max;
    public double stdDev;
    public long windowStart;
    public long windowEnd;

    public TransactionStats() {}
}
