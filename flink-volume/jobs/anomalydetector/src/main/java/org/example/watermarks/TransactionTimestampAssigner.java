package org.example.watermarks;

import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.example.models.Transaction;

public class TransactionTimestampAssigner implements SerializableTimestampAssigner<Transaction> {

    @Override
    public long extractTimestamp(Transaction transaction, long recordTimestamp) {
        return transaction.timestamp;
    }
}