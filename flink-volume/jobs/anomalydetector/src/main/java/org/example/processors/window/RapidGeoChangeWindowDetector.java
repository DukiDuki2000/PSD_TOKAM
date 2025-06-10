package org.example.processors.window;

import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.example.models.AnomalyAlert;
import org.example.models.Transaction;
import org.example.processors.base.BaseWindowAnomalyDetector;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class RapidGeoChangeWindowDetector extends BaseWindowAnomalyDetector<Transaction, Transaction, String> {

    private final OutputTag<AnomalyAlert> rapidGeoAnomalyTag;

    private static final double MIN_DISTANCE_KM = 400.0; // Minimum distance for anomaly in window
    private static final int MIN_GEO_TRANSACTIONS = 2; // Need at least 2 transactions to compare
    private static final String GEO_STATS_PREFIX = "geo_window_stats:";
    private static final int GEO_STATS_TTL = 300; // 5 minutes TTL

    public RapidGeoChangeWindowDetector(OutputTag<AnomalyAlert> rapidGeoAnomalyTag) {
        this.rapidGeoAnomalyTag = rapidGeoAnomalyTag;
    }

    @Override
    public void process(String cardId, Context context, Iterable<Transaction> elements, Collector<Transaction> out) throws Exception {

        List<Transaction> geoTransactions = StreamSupport.stream(elements.spliterator(), false)
                .sorted(Comparator.comparing(t -> t.timestamp))
                .collect(Collectors.toList());


        elements.forEach(out::collect);

        if (geoTransactions.size() < MIN_GEO_TRANSACTIONS) {
            return;
        }

        List<Transaction> rapidGeoTransactions = findRapidGeoChangesInWindow(geoTransactions);

        if (!rapidGeoTransactions.isEmpty()) {
            detectRapidGeoChangeAnomaly(cardId, geoTransactions, rapidGeoTransactions, context);
        }

        if (!geoTransactions.isEmpty()) {
            updateGeoWindowStats(cardId, geoTransactions, context.window());
        }
    }

    private List<Transaction> findRapidGeoChangesInWindow(List<Transaction> transactions) {
        List<Transaction> rapidChanges = new ArrayList<>();

        for (int i = 1; i < transactions.size(); i++) {
            Transaction current = transactions.get(i);
            Transaction previous = transactions.get(i - 1);

            double distance = calculateDistance(
                    previous.location.latitude, previous.location.longitude,
                    current.location.latitude, current.location.longitude
            );

            if (distance >= MIN_DISTANCE_KM) {
                boolean isForeignCurrent = !current.location.countryCode.equals("PL");
                boolean isForeignPrevious = !previous.location.countryCode.equals("PL");

                if (isForeignCurrent || isForeignPrevious) {
                    rapidChanges.add(current);
                }
            }
        }

        return rapidChanges;
    }

    private void detectRapidGeoChangeAnomaly(String cardId, List<Transaction> allTransactions,
                                             List<Transaction> rapidGeoTransactions, Context context) {
        long windowStart = context.window().getStart();
        long windowEnd = context.window().getEnd();
        long windowDurationSeconds = (windowEnd - windowStart) / 1000;


        double maxDistance = 0.0;
        Transaction mostSignificantChange = rapidGeoTransactions.get(0);
        Transaction previousTransaction = null;
        long shortestTimeDiff = Long.MAX_VALUE;

        for (int i = 1; i < allTransactions.size(); i++) {
            Transaction current = allTransactions.get(i);
            Transaction previous = allTransactions.get(i - 1);

            double distance = calculateDistance(
                    previous.location.latitude, previous.location.longitude,
                    current.location.latitude, current.location.longitude
            );

            long timeDiff = current.timestamp - previous.timestamp;

            if (distance >= MIN_DISTANCE_KM && rapidGeoTransactions.contains(current)) {
                if (distance > maxDistance || (distance == maxDistance && timeDiff < shortestTimeDiff)) {
                    maxDistance = distance;
                    mostSignificantChange = current;
                    previousTransaction = previous;
                    shortestTimeDiff = timeDiff;
                }
            }
        }

        if (previousTransaction != null) {
            String description = String.format(
                    "RAPID GEOGRAPHIC CHANGE: %.1f km in %d seconds (%.1f km/h) within %d-second window. " +
                            "From: %s, %s → To: %s, %s. Amount: %.2f PLN.",
                    maxDistance, shortestTimeDiff / 1000, (maxDistance / (shortestTimeDiff / 1000.0)) * 3600, windowDurationSeconds,
                    previousTransaction.location.city, previousTransaction.location.country,
                    mostSignificantChange.location.city, mostSignificantChange.location.country,
                    mostSignificantChange.amount
            );

            double severity = Math.min(0.95, 0.6 + (maxDistance / 1000.0) * 0.1);

            AnomalyAlert alert = new AnomalyAlert(
                    "geo_window_" + UUID.randomUUID().toString().substring(0, 8),
                    mostSignificantChange.transactionId,
                    cardId,
                    mostSignificantChange.userId,
                    "RAPID_GEO_CHANGE_PATTERN",
                    description,
                    severity,
                    windowEnd,
                    mostSignificantChange.location
            );

            context.output(rapidGeoAnomalyTag, alert);

        }
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;

        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private void updateGeoWindowStats(String cardId, List<Transaction> transactions, TimeWindow window) {
        String statsKey = GEO_STATS_PREFIX + cardId;

        GeoWindowStats stats = getFromRedis(statsKey, GeoWindowStats.class);
        if (stats == null) {
            stats = new GeoWindowStats();
        }

        stats.windowCount++;
        stats.totalTransactions += transactions.size();
        stats.lastWindowStart = window.getStart();
        stats.lastWindowEnd = window.getEnd();

        Set<String> uniqueCities = transactions.stream()
                .map(t -> t.location.city)
                .collect(Collectors.toSet());

        Set<String> uniqueCountries = transactions.stream()
                .map(t -> t.location.country)
                .collect(Collectors.toSet());

        stats.uniqueCities = uniqueCities.size();
        stats.uniqueCountries = uniqueCountries.size();

        int foreignTransactions = (int) transactions.stream()
                .filter(t -> !"PL".equals(t.location.countryCode))
                .count();

        stats.foreignTransactions += foreignTransactions;

        double maxDistance = 0.0;
        for (int i = 0; i < transactions.size(); i++) {
            for (int j = i + 1; j < transactions.size(); j++) {
                double distance = calculateDistance(
                        transactions.get(i).location.latitude, transactions.get(i).location.longitude,
                        transactions.get(j).location.latitude, transactions.get(j).location.longitude
                );
                maxDistance = Math.max(maxDistance, distance);
            }
        }
        stats.maxDistanceInWindow = maxDistance;

        List<String> foreignLocations = transactions.stream()
                .filter(t -> !"PL".equals(t.location.countryCode))
                .map(t -> t.location.city + ", " + t.location.country)
                .distinct()
                .collect(Collectors.toList());

        stats.foreignLocations.addAll(foreignLocations);

        storeInRedis(statsKey, stats, GEO_STATS_TTL);
    }

    public static class GeoWindowStats {
        public int windowCount = 0;
        public int totalTransactions = 0;
        public int uniqueCities = 0;
        public int uniqueCountries = 0;
        public int foreignTransactions = 0;
        public double maxDistanceInWindow = 0.0;
        public List<String> foreignLocations = new ArrayList<>();
        public long lastWindowStart = 0;
        public long lastWindowEnd = 0;

        public GeoWindowStats() {}
    }
}