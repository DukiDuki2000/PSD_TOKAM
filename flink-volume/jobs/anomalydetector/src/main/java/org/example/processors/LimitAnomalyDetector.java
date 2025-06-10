package org.example.processors;

import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.CardProfile;
import org.example.models.Transaction;
import org.example.processors.base.BaseAnomalyDetector;
import java.util.UUID;

public class LimitAnomalyDetector extends BaseAnomalyDetector<Transaction, Transaction> {

    private final OutputTag<AnomalyAlert> limitAnomalyTag;

    public LimitAnomalyDetector(OutputTag<AnomalyAlert> limitAnomalyTag) {
        this.limitAnomalyTag = limitAnomalyTag;
    }

    @Override
    public void processElement(Transaction transaction, Context context, Collector<Transaction> out) throws Exception {

        try {
            CardProfile cardProfile = getFromRedis("card_profile:" + transaction.cardId, CardProfile.class);

            if (cardProfile != null) {
                detectInsufficientFundsAnomaly(transaction, cardProfile, context);
                detectTransactionLimitAnomaly(transaction, cardProfile, context);
            }

        } catch (Exception e) {
            System.err.println("Limit anomaly detection error for card " + transaction.cardId + ": " + e.getMessage());
        }

        out.collect(transaction);
    }

    private void detectInsufficientFundsAnomaly(Transaction transaction, CardProfile cardProfile, Context context) {
        if (transaction.amount > cardProfile.currentBalance && "declined".equals(transaction.status)) {
            double excess = transaction.amount - cardProfile.currentBalance;

            String description = String.format(
                    "INSUFFICIENT FUNDS: Transaction %.2f PLN exceeds balance %.2f PLN by %.2f PLN. Status: %s. ",
                    transaction.amount, cardProfile.currentBalance, excess, transaction.status
            );

            AnomalyAlert alert = new AnomalyAlert(
                    "funds_" + UUID.randomUUID().toString().substring(0, 8),
                    transaction.transactionId,
                    transaction.cardId,
                    transaction.userId,
                    "INSUFFICIENT_FUNDS",
                    description,
                    0.7,
                    transaction.timestamp,
                    transaction.location
            );

            context.output(limitAnomalyTag, alert);
        }
    }

    private void detectTransactionLimitAnomaly(Transaction transaction, CardProfile cardProfile, Context context) {
        if (transaction.amount > cardProfile.transactionLimit && "declined".equals(transaction.status)) {
            double excess = transaction.amount - cardProfile.transactionLimit;

            String description = String.format(
                    "TRANSACTION LIMIT EXCEEDED: Transaction %.2f PLN exceeds limit %.2f PLN by %.2f PLN. Status: %s. ",
                    transaction.amount, cardProfile.transactionLimit, excess, transaction.status
            );

            AnomalyAlert alert = new AnomalyAlert(
                    "limit_" + UUID.randomUUID().toString().substring(0, 8),
                    transaction.transactionId,
                    transaction.cardId,
                    transaction.userId,
                    "TRANSACTION_LIMIT_EXCEEDED",
                    description,
                    0.8,
                    transaction.timestamp,
                    transaction.location
            );

            context.output(limitAnomalyTag, alert);
        }
    }

}