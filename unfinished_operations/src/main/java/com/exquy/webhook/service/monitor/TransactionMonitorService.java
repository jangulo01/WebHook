package com.exquy.webhook.service.monitor;

import com.company.transactionrecovery.domain.enums.TransactionStatus;
import com.company.transactionrecovery.domain.enums.WebhookEventType;
import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.domain.repository.TransactionHistoryRepository;
import com.company.transactionrecovery.domain.repository.TransactionRepository;
import com.company.transactionrecovery.domain.service.transaction.StateManagerService;
import com.company.transactionrecovery.domain.service.transaction.TransactionService;
import com.company.transactionrecovery.domain.service.webhook.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service responsible for monitoring transactions and resolving issues.
 * Periodically checks for stalled, timed out, or inconsistent transactions
 * and takes appropriate actions to resolve them.
 */
@Service
public class TransactionMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionMonitorService.class);

    private final TransactionRepository transactionRepository;
    private final TransactionHistoryRepository historyRepository;
    private final TransactionService transactionService;
    private final StateManagerService stateManagerService;
    private final WebhookService webhookService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final AlertService alertService;

    // Flag to indicate if a monitoring task is already running
    private final AtomicBoolean monitoringInProgress = new AtomicBoolean(false);

    @Value("${transaction.monitor.pending-timeout-minutes:5}")
    private int pendingTimeoutMinutes;

    @Value("${transaction.monitor.processing-timeout-minutes:10}")
    private int processingTimeoutMinutes;

    @Value("${transaction.monitor.max-auto-retries:3}")
    private int maxAutoRetries;

    @Autowired
    public TransactionMonitorService(
            TransactionRepository transactionRepository,
            TransactionHistoryRepository historyRepository,
            TransactionService transactionService,
            StateManagerService stateManagerService,
            WebhookService webhookService,
            AnomalyDetectionService anomalyDetectionService,
            AlertService alertService) {
        this.transactionRepository = transactionRepository;
        this.historyRepository = historyRepository;
        this.transactionService = transactionService;
        this.stateManagerService = stateManagerService;
        this.webhookService = webhookService;
        this.anomalyDetectionService = anomalyDetectionService;
        this.alertService = alertService;
    }

    /**
     * Scheduled task that monitors transaction states.
     * Runs automatically at fixed intervals.
     */
    @Scheduled(fixedDelayString = "${transaction.monitor.interval-ms:60000}")
    @Async("monitorExecutor")
    public void scheduledMonitoring() {
        if (monitoringInProgress.compareAndSet(false, true)) {
            try {
                logger.info("Starting scheduled transaction monitoring");
                monitorTransactions();
                logger.info("Completed scheduled transaction monitoring");
            } catch (Exception e) {
                logger.error("Error during scheduled transaction monitoring", e);
                alertService.sendAlert("Monitor Error", 
                        "Error during scheduled transaction monitoring: " + e.getMessage());
            } finally {
                monitoringInProgress.set(false);
            }
        } else {
            logger.info("Skipping scheduled monitoring as previous run is still in progress");
        }
    }

    /**
     * Manually triggers the monitoring process.
     * Useful for administrative purposes or testing.
     *
     * @return A map containing the results of the monitoring process
     */
    @Transactional
    public Map<String, Object> manuallyRunMonitor() {
        logger.info("Manually triggering transaction monitoring");
        
        if (monitoringInProgress.get()) {
            throw new IllegalStateException("Monitoring is already in progress");
        }
        
        Map<String, Object> results = new HashMap<>();
        
        monitoringInProgress.set(true);
        try {
            MonitoringResult monitorResult = monitorTransactions();
            results.put("pending_resolved", monitorResult.pendingResolved);
            results.put("processing_resolved", monitorResult.processingResolved);
            results.put("timeouts_detected", monitorResult.timeoutsDetected);
            results.put("auto_retried", monitorResult.autoRetried);
            results.put("manual_intervention_required", monitorResult.manualInterventionRequired);
        } finally {
            monitoringInProgress.set(false);
        }
        
        return results;
    }

    /**
     * Core monitoring logic.
     * Checks for transactions in various problematic states and resolves them.
     *
     * @return A MonitoringResult object containing statistics about the monitoring run
     */
    @Transactional
    protected MonitoringResult monitorTransactions() {
        MonitoringResult result = new MonitoringResult();
        
        // Check for pending transactions that have timed out
        result.pendingResolved = checkPendingTransactions();
        
        // Check for processing transactions that have timed out
        result.processingResolved = checkProcessingTransactions();
        
        // Check for transactions in timeout or inconsistent state
        result.timeoutsDetected = checkTimeoutTransactions();
        
        // Check for transactions that might need a retry
        result.autoRetried = attemptAutoRetries();
        
        // Check for transactions that need manual intervention
        result.manualInterventionRequired = checkForManualIntervention();
        
        return result;
    }

    /**
     * Checks for pending transactions that have been in that state too long.
     *
     * @return The number of transactions resolved
     */
    private int checkPendingTransactions() {
        LocalDateTime threshold = LocalDateTime.now().minus(pendingTimeoutMinutes, ChronoUnit.MINUTES);
        List<Transaction> stalledTransactions = transactionRepository
                .findByStatusAndCreatedAtBefore(TransactionStatus.PENDING, threshold);
        
        logger.info("Found {} stalled PENDING transactions", stalledTransactions.size());
        
        int resolved = 0;
        for (Transaction tx : stalledTransactions) {
            try {
                // Move to TIMEOUT state
                transactionService.updateTransactionStatus(
                        tx.getId(), 
                        TransactionStatus.TIMEOUT, 
                        "Transaction timed out in PENDING state", 
                        "SYSTEM_MONITOR");
                
                // Send notification
                Map<String, Object> additionalData = new HashMap<>();
                additionalData.put("reason", "Transaction timed out in PENDING state");
                additionalData.put("timeoutThreshold", pendingTimeoutMinutes + " minutes");
                
                webhookService.sendTransactionEventNotification(
                        tx, WebhookEventType.TRANSACTION_TIMEOUT, additionalData);
                
                resolved++;
            } catch (Exception e) {
                logger.error("Error handling stalled PENDING transaction: {}", tx.getId(), e);
                alertService.sendAlert("Monitor Error", 
                        "Error handling stalled PENDING transaction: " + tx.getId());
            }
        }
        
        return resolved;
    }

    /**
     * Checks for processing transactions that have been in that state too long.
     *
     * @return The number of transactions resolved
     */
    private int checkProcessingTransactions() {
        LocalDateTime threshold = LocalDateTime.now().minus(processingTimeoutMinutes, ChronoUnit.MINUTES);
        List<Transaction> stalledTransactions = transactionRepository
                .findByStatusAndCreatedAtBefore(TransactionStatus.PROCESSING, threshold);
        
        logger.info("Found {} stalled PROCESSING transactions", stalledTransactions.size());
        
        int resolved = 0;
        for (Transaction tx : stalledTransactions) {
            try {
                // Attempt to determine the actual state
                TransactionStatus actualState = stateManagerService.determineActualState(tx);
                
                if (actualState != tx.getStatus()) {
                    // Update to the determined state
                    transactionService.updateTransactionStatus(
                            tx.getId(), 
                            actualState, 
                            "State determined by monitor after PROCESSING timeout", 
                            "SYSTEM_MONITOR");
                    
                    // Send notification
                    Map<String, Object> additionalData = new HashMap<>();
                    additionalData.put("previousStatus", TransactionStatus.PROCESSING.toString());
                    additionalData.put("reason", "Processing timeout - state determined by system");
                    additionalData.put("timeoutThreshold", processingTimeoutMinutes + " minutes");
                    
                    WebhookEventType eventType = (actualState == TransactionStatus.COMPLETED) 
                            ? WebhookEventType.TRANSACTION_COMPLETED 
                            : WebhookEventType.TRANSACTION_STATUS_CHANGED;
                    
                    webhookService.sendTransactionEventNotification(
                            tx, eventType, additionalData);
                    
                    resolved++;
                } else {
                    // If we couldn't determine a better state, move to TIMEOUT
                    transactionService.updateTransactionStatus(
                            tx.getId(), 
                            TransactionStatus.TIMEOUT, 
                            "Transaction timed out in PROCESSING state", 
                            "SYSTEM_MONITOR");
                    
                    // Send notification
                    Map<String, Object> additionalData = new HashMap<>();
                    additionalData.put("reason", "Transaction timed out in PROCESSING state");
                    additionalData.put("timeoutThreshold", processingTimeoutMinutes + " minutes");
                    
                    webhookService.sendTransactionEventNotification(
                            tx, WebhookEventType.TRANSACTION_TIMEOUT, additionalData);
                    
                    resolved++;
                }
            } catch (Exception e) {
                logger.error("Error handling stalled PROCESSING transaction: {}", tx.getId(), e);
                alertService.sendAlert("Monitor Error", 
                        "Error handling stalled PROCESSING transaction: " + tx.getId());
            }
        }
        
        return resolved;
    }

    /**
     * Checks transactions in TIMEOUT or INCONSISTENT state and attempts to resolve them.
     *
     * @return The number of transactions resolved
     */
    private int checkTimeoutTransactions() {
        List<Transaction> timeoutTransactions = transactionRepository
                .findByStatus(TransactionStatus.TIMEOUT, null).getContent();
        
        List<Transaction> inconsistentTransactions = transactionRepository
                .findByStatus(TransactionStatus.INCONSISTENT, null).getContent();
        
        logger.info("Found {} TIMEOUT and {} INCONSISTENT transactions", 
                timeoutTransactions.size(), inconsistentTransactions.size());
        
        int resolved = 0;
        
        // Process timeout transactions
        for (Transaction tx : timeoutTransactions) {
            try {
                // Try to reconcile if not already reconciled
                if (!tx.getIsReconciled()) {
                    transactionService.reconcileTransaction(tx.getId());
                    resolved++;
                }
            } catch (Exception e) {
                logger.error("Error reconciling TIMEOUT transaction: {}", tx.getId(), e);
                alertService.sendAlert("Monitor Error", 
                        "Error reconciling TIMEOUT transaction: " + tx.getId());
            }
        }
        
        // Process inconsistent transactions
        for (Transaction tx : inconsistentTransactions) {
            try {
                // Try to reconcile if not already reconciled
                if (!tx.getIsReconciled()) {
                    transactionService.reconcileTransaction(tx.getId());
                    resolved++;
                }
            } catch (Exception e) {
                logger.error("Error reconciling INCONSISTENT transaction: {}", tx.getId(), e);
                alertService.sendAlert("Monitor Error", 
                        "Error reconciling INCONSISTENT transaction: " + tx.getId());
            }
        }
        
        return resolved;
    }

    /**
     * Attempts to automatically retry eligible transactions.
     *
     * @return The number of transactions retried
     */
    private int attemptAutoRetries() {
        // Find all transactions that are eligible for retry
        List<Transaction> retryableTransactions = transactionRepository
                .findByStatus(TransactionStatus.PENDING, null).getContent();
        
        retryableTransactions.addAll(transactionRepository
                .findByStatus(TransactionStatus.TIMEOUT, null).getContent());
        
        logger.info("Checking {} transactions for auto-retry eligibility", retryableTransactions.size());
        
        int retried = 0;
        
        for (Transaction tx : retryableTransactions) {
            // Skip if max attempts reached
            if (tx.getAttemptCount() >= maxAutoRetries) {
                continue;
            }
            
            // Check if this transaction should be retried
            if (stateManagerService.shouldRetry(tx)) {
                try {
                    // Record attempt and trigger processing
                    transactionService.recordTransactionAttempt(tx.getId());
                    
                    // Send notification
                    Map<String, Object> additionalData = new HashMap<>();
                    additionalData.put("attempt", tx.getAttemptCount() + 1);
                    additionalData.put("maxAttempts", maxAutoRetries);
                    additionalData.put("previousStatus", tx.getStatus().toString());
                    
                    webhookService.sendTransactionEventNotification(
                            tx, WebhookEventType.TRANSACTION_RETRY, additionalData);
                    
                    retried++;
                } catch (Exception e) {
                    logger.error("Error auto-retrying transaction: {}", tx.getId(), e);
                    alertService.sendAlert("Monitor Error", 
                            "Error auto-retrying transaction: " + tx.getId());
                }
            }
        }
        
        return retried;
    }

    /**
     * Identifies transactions that require manual intervention.
     *
     * @return The number of transactions requiring manual intervention
     */
    private int checkForManualIntervention() {
        // Find anomalous transactions
        List<Transaction> anomalies = anomalyDetectionService.detectAnomalousTransactions();
        
        logger.info("Found {} transactions requiring possible manual intervention", anomalies.size());
        
        if (!anomalies.isEmpty()) {
            // Send alert to administrators
            alertService.sendAlert("Manual Intervention Required", 
                    "Found " + anomalies.size() + " transactions that may require manual intervention.");
            
            // Send detailed report for each transaction
            for (Transaction tx : anomalies) {
                alertService.sendTransactionAlert(tx);
            }
        }
        
        return anomalies.size();
    }

    /**
     * Runs a reconciliation process to detect and fix inconsistencies.
     * This is typically used after system failures or maintenance.
     *
     * @return Results of the reconciliation process
     */
    @Transactional
    public Map<String, Object> runReconciliationProcess() {
        logger.info("Starting system-wide reconciliation process");
        
        Map<String, Object> results = new HashMap<>();
        
        // Find all transactions in non-terminal states
        List<Transaction> pendingTransactions = transactionRepository
                .findByStatus(TransactionStatus.PENDING, null).getContent();
        
        List<Transaction> processingTransactions = transactionRepository
                .findByStatus(TransactionStatus.PROCESSING, null).getContent();
        
        List<Transaction> timeoutTransactions = transactionRepository
                .findByStatus(TransactionStatus.TIMEOUT, null).getContent();
        
        List<Transaction> inconsistentTransactions = transactionRepository
                .findByStatus(TransactionStatus.INCONSISTENT, null).getContent();
        
        int reconciled = 0;
        
        // Process all pending transactions
        for (Transaction tx : pendingTransactions) {
            try {
                transactionService.reconcileTransaction(tx.getId());
                reconciled++;
            } catch (Exception e) {
                logger.error("Error reconciling PENDING transaction: {}", tx.getId(), e);
            }
        }
        
        // Process all processing transactions
        for (Transaction tx : processingTransactions) {
            try {
                transactionService.reconcileTransaction(tx.getId());
                reconciled++;
            } catch (Exception e) {
                logger.error("Error reconciling PROCESSING transaction: {}", tx.getId(), e);
            }
        }
        
        // Process all timeout transactions
        for (Transaction tx : timeoutTransactions) {
            try {
                transactionService.reconcileTransaction(tx.getId());
                reconciled++;
            } catch (Exception e) {
                logger.error("Error reconciling TIMEOUT transaction: {}", tx.getId(), e);
            }
        }
        
        // Process all inconsistent transactions
        for (Transaction tx : inconsistentTransactions) {
            try {
                transactionService.reconcileTransaction(tx.getId());
                reconciled++;
            } catch (Exception e) {
                logger.error("Error reconciling INCONSISTENT transaction: {}", tx.getId(), e);
            }
        }
        
        results.put("total_transactions_processed", 
                pendingTransactions.size() + processingTransactions.size() + 
                timeoutTransactions.size() + inconsistentTransactions.size());
        
        results.put("transactions_reconciled", reconciled);
        
        // Check for any remaining anomalies
        List<Transaction> remainingAnomalies = anomalyDetectionService.detectAnomalousTransactions();
        results.put("transactions_requiring_manual_intervention", remainingAnomalies.size());
        
        logger.info("Completed system-wide reconciliation process. Reconciled {} transactions.", 
                reconciled);
        
        return results;
    }

    /**
     * Gets the current system metrics.
     *
     * @return Map of metrics
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSystemMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        // Get transaction counts by status
        List<TransactionRepository.StatusCount> statusCounts = transactionRepository.countByStatus();
        Map<String, Long> countsByStatus = new HashMap<>();
        
        for (TransactionRepository.StatusCount sc : statusCounts) {
            countsByStatus.put(sc.getStatus().toString(), sc.getCount());
        }
        
        metrics.put("transactions_by_status", countsByStatus);
        
        // Get count of anomalous transactions
        List<Transaction> anomalies = anomalyDetectionService.detectAnomalousTransactions();
        metrics.put("anomalous_transactions", anomalies.size());
        
        // Calculate processing efficiency
        long totalTransactions = transactionRepository.count();
        long completedTransactions = countsByStatus.getOrDefault("COMPLETED", 0L);
        long failedTransactions = countsByStatus.getOrDefault("FAILED", 0L);
        
        double completionRate = totalTransactions > 0 
                ? (double) completedTransactions / totalTransactions * 100 
                : 0;
        
        double failureRate = totalTransactions > 0 
                ? (double) failedTransactions / totalTransactions * 100 
                : 0;
        
        metrics.put("completion_rate", Math.round(completionRate * 100) / 100.0);
        metrics.put("failure_rate", Math.round(failureRate * 100) / 100.0);
        
        // Add webhook metrics
        metrics.put("webhook_metrics", webhookService.getDeliveryStatistics());
        
        return metrics;
    }

    /**
     * Gets system statistics for a specific time period.
     *
     * @param startDate Start of the period
     * @param endDate End of the period
     * @return Map of statistics
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSystemStatistics(LocalDateTime startDate, LocalDateTime endDate) {
        Map<String, Object> stats = new HashMap<>();
        
        // Get transaction statistics
        List<Object[]> transactionStats = transactionRepository
                .getTransactionStatistics(startDate, endDate);
        
        stats.put("transaction_statistics", transactionStats);
        
        // Get status transition counts
        List<TransactionHistoryRepository.StatusCount> transitionCounts = historyRepository
                .countStatusTransitionsByPeriod(startDate, endDate);
        
        Map<String, Long> countsByStatus = new HashMap<>();
        for (TransactionHistoryRepository.StatusCount sc : transitionCounts) {
            countsByStatus.put(sc.getStatus().toString(), sc.getCount());
        }
        
        stats.put("status_transitions", countsByStatus);
        
        // Get average time in each status
        List<Object[]> averageTimes = historyRepository
                .calculateAverageTimeInStatus(startDate, endDate);
        
        stats.put("average_time_in_status", averageTimes);
        
        return stats;
    }

    /**
     * Class to hold monitoring run results.
     */
    protected static class MonitoringResult {
        int pendingResolved;
        int processingResolved;
        int timeoutsDetected;
        int autoRetried;
        int manualInterventionRequired;
    }
}
