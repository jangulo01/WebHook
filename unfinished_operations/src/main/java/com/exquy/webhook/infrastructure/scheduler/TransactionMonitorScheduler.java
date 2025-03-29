package com.exquy.webhook.infrastructure.scheduler;

import com.company.transactionrecovery.domain.service.monitor.AlertService;
import com.company.transactionrecovery.domain.service.monitor.AnomalyDetectionService;
import com.company.transactionrecovery.domain.service.monitor.TransactionMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler for transaction monitoring tasks.
 * Handles periodic execution of monitoring, anomaly detection,
 * and system health checks.
 */
@Component
public class TransactionMonitorScheduler {

    private static final Logger logger = LoggerFactory.getLogger(TransactionMonitorScheduler.class);

    private final TransactionMonitorService monitorService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final AlertService alertService;

    // Flags to track task execution
    private final AtomicBoolean monitoringInProgress = new AtomicBoolean(false);
    private final AtomicBoolean anomalyDetectionInProgress = new AtomicBoolean(false);
    private final AtomicBoolean healthCheckInProgress = new AtomicBoolean(false);

    @Value("${scheduler.health-check.enabled:true}")
    private boolean healthCheckEnabled;

    @Value("${scheduler.anomaly-detection.threshold:5}")
    private int anomalyThreshold;

    @Autowired
    public TransactionMonitorScheduler(
            TransactionMonitorService monitorService,
            AnomalyDetectionService anomalyDetectionService,
            AlertService alertService) {
        this.monitorService = monitorService;
        this.anomalyDetectionService = anomalyDetectionService;
        this.alertService = alertService;
    }

    /**
     * Scheduled task that runs transaction monitoring.
     * This is the main monitoring process that checks for stalled transactions.
     * Runs every minute by default.
     */
    @Scheduled(cron = "${scheduler.monitor.cron:0 * * * * *}")
    public void runMonitoringTask() {
        if (monitoringInProgress.compareAndSet(false, true)) {
            try {
                logger.debug("Starting scheduled transaction monitoring");
                monitorService.manuallyRunMonitor();
                logger.debug("Completed scheduled transaction monitoring");
            } catch (Exception e) {
                logger.error("Error during scheduled transaction monitoring", e);
                alertService.sendAlert("Monitor Error", 
                        "Error during scheduled transaction monitoring: " + e.getMessage());
            } finally {
                monitoringInProgress.set(false);
            }
        } else {
            logger.debug("Skipping scheduled monitoring as previous run is still in progress");
        }
    }

    /**
     * Scheduled task that runs anomaly detection.
     * Looks for unusual transaction patterns that might indicate problems.
     * Runs every 5 minutes by default.
     */
    @Scheduled(cron = "${scheduler.anomaly-detection.cron:0 */5 * * * *}")
    public void runAnomalyDetection() {
        if (anomalyDetectionInProgress.compareAndSet(false, true)) {
            try {
                logger.debug("Starting scheduled anomaly detection");
                
                // Get high priority anomalies
                int limit = 10; // Limit to top 10 anomalies
                var anomalies = anomalyDetectionService.getPrioritizedAnomalies(limit);
                
                logger.info("Detected {} anomalous transactions", anomalies.size());
                
                // If above threshold, send alert
                if (anomalies.size() > anomalyThreshold) {
                    for (var transaction : anomalies) {
                        alertService.sendTransactionAlert(transaction);
                    }
                }
                
                logger.debug("Completed scheduled anomaly detection");
            } catch (Exception e) {
                logger.error("Error during scheduled anomaly detection", e);
                alertService.sendAlert("Anomaly Detection Error", 
                        "Error during scheduled anomaly detection: " + e.getMessage());
            } finally {
                anomalyDetectionInProgress.set(false);
            }
        } else {
            logger.debug("Skipping scheduled anomaly detection as previous run is still in progress");
        }
    }

    /**
     * Scheduled task that checks system health.
     * Gathers metrics and sends health status reports.
     * Runs daily by default.
     */
    @Scheduled(cron = "${scheduler.health-check.cron:0 0 0 * * *}")
    public void runSystemHealthCheck() {
        if (!healthCheckEnabled) {
            logger.debug("System health check is disabled");
            return;
        }
        
        if (healthCheckInProgress.compareAndSet(false, true)) {
            try {
                logger.info("Starting scheduled system health check");
                
                // Get system metrics
                Map<String, Object> metrics = monitorService.getSystemMetrics();
                
                // Get anomaly statistics
                Map<String, Object> anomalyStats = anomalyDetectionService.getAnomalyStatistics();
                
                // Send health report
                alertService.sendSystemHealthAlert(metrics, anomalyStats);
                
                logger.info("Completed scheduled system health check");
            } catch (Exception e) {
                logger.error("Error during scheduled system health check", e);
                alertService.sendCriticalErrorAlert(e, "During scheduled system health check");
            } finally {
                healthCheckInProgress.set(false);
            }
        } else {
            logger.debug("Skipping scheduled health check as previous run is still in progress");
        }
    }

    /**
     * Scheduled task that runs daily reconciliation process.
     * This task attempts to resolve any lingering inconsistencies.
     * Runs at 1 AM daily by default.
     */
    @Scheduled(cron = "${scheduler.reconciliation.cron:0 0 1 * * *}")
    public void runDailyReconciliation() {
        logger.info("Starting scheduled daily reconciliation process");
        
        try {
            Map<String, Object> results = monitorService.runReconciliationProcess();
            
            int processed = (int) results.getOrDefault("total_transactions_processed", 0);
            int reconciled = (int) results.getOrDefault("transactions_reconciled", 0);
            int manual = (int) results.getOrDefault("transactions_requiring_manual_intervention", 0);
            
            logger.info("Daily reconciliation completed: processed={}, reconciled={}, manual={}",
                    processed, reconciled, manual);
            
            // If there are transactions requiring manual intervention, send alert
            if (manual > 0) {
                alertService.sendAlert("Reconciliation Results", 
                        String.format("Daily reconciliation found %d transaction(s) " +
                                "requiring manual intervention.", manual));
            }
        } catch (Exception e) {
            logger.error("Error during scheduled daily reconciliation", e);
            alertService.sendCriticalErrorAlert(e, "During scheduled daily reconciliation");
        }
    }

    /**
     * Gets information about scheduler status.
     *
     * @return Map containing status information
     */
    public Map<String, Object> getSchedulerStatus() {
        return Map.of(
                "monitoringInProgress", monitoringInProgress.get(),
                "anomalyDetectionInProgress", anomalyDetectionInProgress.get(),
                "healthCheckInProgress", healthCheckInProgress.get(),
                "healthCheckEnabled", healthCheckEnabled,
                "anomalyThreshold", anomalyThreshold
        );
    }

    /**
     * Enables or disables the health check.
     *
     * @param enabled true to enable, false to disable
     */
    public void setHealthCheckEnabled(boolean enabled) {
        this.healthCheckEnabled = enabled;
        logger.info("System health check {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Sets the anomaly threshold for alerts.
     *
     * @param threshold The new threshold
     */
    public void setAnomalyThreshold(int threshold) {
        this.anomalyThreshold = threshold;
        logger.info("Anomaly alert threshold set to {}", threshold);
    }
}
