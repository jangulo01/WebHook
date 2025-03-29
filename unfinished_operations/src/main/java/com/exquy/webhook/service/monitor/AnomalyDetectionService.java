package com.exquy.webhook.service.monitor;

import com.company.transactionrecovery.domain.enums.TransactionStatus;
import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.domain.model.TransactionHistory;
import com.company.transactionrecovery.domain.repository.TransactionHistoryRepository;
import com.company.transactionrecovery.domain.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service responsible for detecting anomalous transactions.
 * Analyzes transaction data to find unusual patterns, states, or behaviors
 * that might indicate problems requiring attention.
 */
@Service
public class AnomalyDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(AnomalyDetectionService.class);

    private final TransactionRepository transactionRepository;
    private final TransactionHistoryRepository historyRepository;

    @Value("${anomaly.pending-threshold-minutes:30}")
    private int pendingThresholdMinutes;

    @Value("${anomaly.processing-threshold-minutes:60}")
    private int processingThresholdMinutes;

    @Value("${anomaly.retry-threshold:5}")
    private int retryThreshold;

    @Value("${anomaly.state-change-threshold:10}")
    private int stateChangeThreshold;

    @Autowired
    public AnomalyDetectionService(
            TransactionRepository transactionRepository,
            TransactionHistoryRepository historyRepository) {
        this.transactionRepository = transactionRepository;
        this.historyRepository = historyRepository;
    }

    /**
     * Detects transactions in anomalous states.
     *
     * @return List of anomalous transactions
     */
    @Transactional(readOnly = true)
    public List<Transaction> detectAnomalousTransactions() {
        logger.debug("Detecting anomalous transactions");
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime pendingThreshold = now.minusMinutes(pendingThresholdMinutes);
        LocalDateTime processingThreshold = now.minusMinutes(processingThresholdMinutes);
        
        // Use the repository's dedicated method for finding anomalies
        List<Transaction> anomalies = transactionRepository.findAnomalousTransactions(pendingThreshold);
        
        // Add transactions with excessive retries but not in failed state
        List<Transaction> excessiveRetries = findTransactionsWithExcessiveRetries();
        
        // Add transactions with excessive state changes
        List<Transaction> excessiveStateChanges = findTransactionsWithExcessiveStateChanges();
        
        // Combine all anomalies, making sure there are no duplicates
        Map<String, Transaction> anomalyMap = new HashMap<>();
        
        anomalies.forEach(tx -> anomalyMap.put(tx.getId().toString(), tx));
        excessiveRetries.forEach(tx -> anomalyMap.put(tx.getId().toString(), tx));
        excessiveStateChanges.forEach(tx -> anomalyMap.put(tx.getId().toString(), tx));
        
        List<Transaction> allAnomalies = new ArrayList<>(anomalyMap.values());
        
        logger.info("Detected {} anomalous transactions", allAnomalies.size());
        return allAnomalies;
    }

    /**
     * Finds transactions with excessive retry attempts.
     *
     * @return List of transactions with excessive retries
     */
    private List<Transaction> findTransactionsWithExcessiveRetries() {
        return transactionRepository.findAll().stream()
                .filter(tx -> tx.getAttemptCount() >= retryThreshold)
                .filter(tx -> tx.getStatus() != TransactionStatus.COMPLETED && 
                              tx.getStatus() != TransactionStatus.FAILED)
                .collect(Collectors.toList());
    }

    /**
     * Finds transactions with excessive state changes.
     *
     * @return List of transactions with excessive state changes
     */
    private List<Transaction> findTransactionsWithExcessiveStateChanges() {
        List<Transaction> result = new ArrayList<>();
        
        List<Transaction> allTransactions = transactionRepository.findAll();
        
        for (Transaction tx : allTransactions) {
            List<TransactionHistory> history = historyRepository
                    .findByTransactionIdOrderByChangedAtAsc(tx.getId());
            
            if (history.size() >= stateChangeThreshold) {
                result.add(tx);
            }
        }
        
        return result;
    }

    /**
     * Analyzes a specific transaction for anomalies.
     *
     * @param transaction The transaction to analyze
     * @return Map of detected anomalies and their descriptions
     */
    @Transactional(readOnly = true)
    public Map<String, String> analyzeTransaction(Transaction transaction) {
        Map<String, String> anomalies = new HashMap<>();
        
        // Check current state
        if (transaction.getStatus() == TransactionStatus.INCONSISTENT) {
            anomalies.put("inconsistent_state", "Transaction is in an explicitly inconsistent state");
        }
        
        if (transaction.getStatus() == TransactionStatus.TIMEOUT) {
            anomalies.put("timeout_state", "Transaction has timed out");
        }
        
        // Check for duration anomalies
        LocalDateTime now = LocalDateTime.now();
        
        if (transaction.getStatus() == TransactionStatus.PENDING) {
            Duration pendingDuration = Duration.between(transaction.getCreatedAt(), now);
            
            if (pendingDuration.toMinutes() > pendingThresholdMinutes) {
                anomalies.put("long_pending", 
                        "Transaction has been pending for " + pendingDuration.toMinutes() + 
                        " minutes (threshold: " + pendingThresholdMinutes + " minutes)");
            }
        }
        
        if (transaction.getStatus() == TransactionStatus.PROCESSING) {
            LocalDateTime lastUpdate = transaction.getLastAttemptAt() != null 
                    ? transaction.getLastAttemptAt() 
                    : transaction.getCreatedAt();
                    
            Duration processingDuration = Duration.between(lastUpdate, now);
            
            if (processingDuration.toMinutes() > processingThresholdMinutes) {
                anomalies.put("long_processing", 
                        "Transaction has been processing for " + processingDuration.toMinutes() + 
                        " minutes (threshold: " + processingThresholdMinutes + " minutes)");
            }
        }
        
        // Check retry count
        if (transaction.getAttemptCount() >= retryThreshold) {
            anomalies.put("excessive_retries", 
                    "Transaction has been retried " + transaction.getAttemptCount() + 
                    " times (threshold: " + retryThreshold + ")");
        }
        
        // Check state changes
        List<TransactionHistory> history = historyRepository
                .findByTransactionIdOrderByChangedAtAsc(transaction.getId());
                
        if (history.size() >= stateChangeThreshold) {
            anomalies.put("excessive_state_changes", 
                    "Transaction has undergone " + history.size() + 
                    " state changes (threshold: " + stateChangeThreshold + ")");
        }
        
        // Check for unstable state (oscillating between states)
        if (history.size() >= 3) {
            Map<String, Integer> stateTransitions = new HashMap<>();
            
            for (int i = 1; i < history.size(); i++) {
                TransactionStatus from = history.get(i-1).getNewStatus();
                TransactionStatus to = history.get(i).getNewStatus();
                
                String transition = from + "->" + to;
                stateTransitions.put(transition, stateTransitions.getOrDefault(transition, 0) + 1);
            }
            
            // Check if any transition has occurred multiple times
            for (Map.Entry<String, Integer> entry : stateTransitions.entrySet()) {
                if (entry.getValue() > 2) {
                    anomalies.put("oscillating_states", 
                            "Transaction is oscillating between states. The transition " + 
                            entry.getKey() + " has occurred " + entry.getValue() + " times");
                    break;
                }
            }
        }
        
        // Check for inconsistent data
        if (transaction.getStatus() == TransactionStatus.COMPLETED && transaction.getResponse() == null) {
            anomalies.put("missing_response", 
                    "Transaction is marked as COMPLETED but has no response data");
        }
        
        if (transaction.getStatus() == TransactionStatus.FAILED && transaction.getErrorDetails() == null) {
            anomalies.put("missing_error_details", 
                    "Transaction is marked as FAILED but has no error details");
        }
        
        // Check for webhook anomalies
        if (transaction.hasWebhookEnabled() && 
            (transaction.getStatus() == TransactionStatus.COMPLETED || 
             transaction.getStatus() == TransactionStatus.FAILED)) {
            
            // Note: In a complete implementation, we would check webhook delivery status here
            // For now, just flag transactions with webhooks for manual verification
            anomalies.put("webhook_verification_needed", 
                    "Transaction has webhooks enabled - verify delivery status manually");
        }
        
        return anomalies;
    }

    /**
     * Gets a prioritized list of anomalous transactions.
     * Sorts anomalies by severity and recency.
     *
     * @param limit Maximum number of transactions to return
     * @return List of prioritized anomalous transactions
     */
    @Transactional(readOnly = true)
    public List<Transaction> getPrioritizedAnomalies(int limit) {
        List<Transaction> anomalies = detectAnomalousTransactions();
        
        // Sort by priority (more anomaly types = higher priority)
        return anomalies.stream()
                .sorted((tx1, tx2) -> {
                    Map<String, String> anomalies1 = analyzeTransaction(tx1);
                    Map<String, String> anomalies2 = analyzeTransaction(tx2);
                    
                    // First compare by number of anomalies
                    int comparison = Integer.compare(anomalies2.size(), anomalies1.size());
                    
                    if (comparison == 0) {
                        // If equal, sort by recency (newer first)
                        return tx2.getUpdatedAt().compareTo(tx1.getUpdatedAt());
                    }
                    
                    return comparison;
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Gets statistics about detected anomalies.
     *
     * @return Map of anomaly statistics
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAnomalyStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        List<Transaction> anomalies = detectAnomalousTransactions();
        
        stats.put("total_anomalies", anomalies.size());
        
        // Count by type
        Map<String, Integer> anomalyTypes = new HashMap<>();
        
        for (Transaction tx : anomalies) {
            Map<String, String> txAnomalies = analyzeTransaction(tx);
            
            for (String type : txAnomalies.keySet()) {
                anomalyTypes.put(type, anomalyTypes.getOrDefault(type, 0) + 1);
            }
        }
        
        stats.put("anomaly_types", anomalyTypes);
        
        // Count by status
        Map<TransactionStatus, Long> statusCounts = anomalies.stream()
                .collect(Collectors.groupingBy(
                        Transaction::getStatus, 
                        Collectors.counting()));
                        
        stats.put("status_distribution", statusCounts);
        
        return stats;
    }
}
