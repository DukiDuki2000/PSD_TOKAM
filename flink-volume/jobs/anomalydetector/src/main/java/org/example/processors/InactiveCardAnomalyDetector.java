package org.example.processors;

import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.CardProfile;
import org.example.models.Transaction;
import org.example.processors.base.BaseAnomalyDetector;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class InactiveCardAnomalyDetector extends BaseAnomalyDetector<Transaction, Transaction> {

    private final OutputTag<AnomalyAlert> inactiveCardAnomalyTag;

    public InactiveCardAnomalyDetector(OutputTag<AnomalyAlert> inactiveCardAnomalyTag) {
        this.inactiveCardAnomalyTag = inactiveCardAnomalyTag;
    }

    @Override
    public void processElement(Transaction transaction, Context context, Collector<Transaction> out) throws Exception {
        try {
            CardProfile cardProfile = getFromRedis("card_profile:" + transaction.cardId, CardProfile.class);

            if (cardProfile != null) {
                ExpiryResult expiryResult = checkCardExpiry(cardProfile, transaction);

                if (expiryResult.isExpired) {
                    detectExpiredCardAnomaly(transaction, expiryResult, context);
                } else if (!cardProfile.isActive) {
                    detectInactiveCardAnomaly(transaction, context);
                }
            }
        } catch (Exception e) {
            System.err.println("Inactive card anomaly detection error for card " + transaction.cardId + ": " + e.getMessage());
        }

        out.collect(transaction);
    }

    private ExpiryResult checkCardExpiry(CardProfile cardProfile, Transaction transaction) {
        try {
            LocalDateTime expiryDate = LocalDateTime.parse(cardProfile.expiryDate, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            LocalDateTime transactionTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(transaction.timestamp),
                    java.time.ZoneOffset.UTC
            );

            boolean isExpired = expiryDate.isBefore(transactionTime);
            long daysExpired = isExpired ? java.time.Duration.between(expiryDate, transactionTime).toDays() : 0;

            return new ExpiryResult(isExpired, daysExpired, expiryDate, transactionTime);
        } catch (Exception e) {
            return new ExpiryResult(false, 0, null, null);
        }
    }

    private void detectInactiveCardAnomaly(Transaction transaction, Context context) {
        String description = String.format(
                "INACTIVE CARD: Transaction attempted on inactive card %s. " +
                        "Amount: %.2f PLN. Status: %s. Card not expired but marked as inactive.",
                transaction.cardId, transaction.amount, transaction.status
        );

        AnomalyAlert alert = new AnomalyAlert(
                "inactive_" + UUID.randomUUID().toString().substring(0, 8),
                transaction.transactionId,
                transaction.cardId,
                transaction.userId,
                "INACTIVE_CARD",
                description,
                0.85,
                transaction.timestamp,
                transaction.location
        );

        context.output(inactiveCardAnomalyTag, alert);

    }

    private void detectExpiredCardAnomaly(Transaction transaction, ExpiryResult expiryResult, Context context) {
        String description = String.format(
                "EXPIRED CARD: Transaction attempted on expired card %s " +
                        "(expired %d days before transaction). Amount: %.2f PLN. Status: %s.",
                transaction.cardId, expiryResult.daysExpired, transaction.amount, transaction.status
        );

        AnomalyAlert alert = new AnomalyAlert(
                "expired_" + UUID.randomUUID().toString().substring(0, 8),
                transaction.transactionId,
                transaction.cardId,
                transaction.userId,
                "EXPIRED_CARD",
                description,
                0.95,
                transaction.timestamp,
                transaction.location
        );

        context.output(inactiveCardAnomalyTag, alert);

    }


    private static class ExpiryResult {
        final boolean isExpired;
        final long daysExpired;
        final LocalDateTime expiryDate;
        final LocalDateTime transactionTime;

        ExpiryResult(boolean isExpired, long daysExpired, LocalDateTime expiryDate, LocalDateTime transactionTime) {
            this.isExpired = isExpired;
            this.daysExpired = daysExpired;
            this.expiryDate = expiryDate;
            this.transactionTime = transactionTime;
        }
    }
}