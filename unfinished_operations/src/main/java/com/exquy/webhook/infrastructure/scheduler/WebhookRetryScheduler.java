package com.exquy.webhook.infrastructure.scheduler;

import com.company.transactionrecovery.domain.model.WebhookDelivery;
import com.company.transactionrecovery.domain.repository.WebhookDeliveryRepository;
import com.company.transactionrecovery.domain.service.monitor.AlertService;
import com.company.transactionrecovery.domain.service.webhook.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler for webhook retry operations.
 * Handles the periodic retry of failed webhook deliveries.
 */
@Component
public class WebhookRetryScheduler {

    private static final Logger logger = LoggerFactory.getLogger(WebhookRetryScheduler.class);

    private final WebhookService webhookService;
    private final WebhookDeliveryRepository deliveryRepository;
    private final AlertService alertService;

    // Flag to track if retry process is running
    private final AtomicBoolean retryInProgress = new AtomicBoolean(false);

    @Value("${webhook.retry.batch-size:50}")
    private int retryBatchSize;

    @Value("${webhook.retry.max-age-hours:24}")
    private int maxAgeHours;

    @Value("${webhook.retry.hang-timeout-minutes:30}")
    private int hangTimeoutMinutes;

    @Value("${webhook.retry.enabled:true}")
    private boolean retryEnabled;

    @Autowired
    public WebhookRetryScheduler(
            WebhookService webhookService,
            WebhookDeliveryRepository deliveryRepository,
            AlertService alertService) {
        this.webhookService = webhookService;
        this.deliveryRepository = deliveryRepository;
        this.alertService = alertService;
    }

    /**
     * Scheduled task that processes webhook deliveries due for retry.
     * Runs every minute by default.
     */
    @Scheduled(cron = "${scheduler.webhook-retry.cron:0 * * * * *}")
    @Transactional
    public void processScheduledRetries() {
        if (!retryEnabled) {
            logger.debug("Webhook retry is disabled");
            return;
        }

        if (retryInProgress.compareAndSet(false, true)) {
            try {
                logger.debug("Starting scheduled webhook retry processing");
                
                int processed = webhookService.processScheduledRetries();
                
                if (processed > 0) {
                    logger.info("Processed {} webhook retries", processed);
                } else {
                    logger.debug("No webhook retries were due");
                }
                
            } catch (Exception e) {
                logger.error("Error during scheduled webhook retry processing", e);
                alertService.sendAlert("Webhook Retry Error", 
                        "Error during scheduled webhook retry processing: " + e.getMessage());
            } finally {
                retryInProgress.set(false);
            }
        } else {
            logger.debug("Skipping scheduled webhook retry as previous run is still in progress");
        }
    }

    /**
     * Scheduled task that checks for webhook deliveries in a hanging state.
     * This identifies deliveries that have been in PROCESSING state for too long.
     * Runs every 10 minutes by default.
     */
    @Scheduled(cron = "${scheduler.webhook-hanging.cron:0 */10 * * * *}")
    @Transactional
    public void checkHangingDeliveries() {
        if (!retryEnabled) {
            return;
        }

        try {
            logger.debug("Checking for hanging webhook deliveries");
            
            LocalDateTime thresholdTime = LocalDateTime.now().minusMinutes(hangTimeoutMinutes);
            List<WebhookDelivery> hangingDeliveries = deliveryRepository.findHangingDeliveries(thresholdTime);
            
            logger.info("Found {} hanging webhook deliveries", hangingDeliveries.size());
            
            for (WebhookDelivery delivery : hangingDeliveries) {
                try {
                    // Mark as failed and schedule for retry
                    delivery.recordError(com.company.transactionrecovery.domain.enums.WebhookDeliveryStatus.FAILED, 
                            Map.of("reason", "Processing timeout after " + hangTimeoutMinutes + " minutes"));
                            
                    int delaySeconds = webhookService.computeNextRetryDelay(delivery);
                    delivery.scheduleRetry(LocalDateTime.now().plusSeconds(delaySeconds));
                    
                    deliveryRepository.save(delivery);
                    
                    logger.info("Rescheduled hanging webhook delivery: {}", delivery.getId());
                } catch (Exception e) {
                    logger.error("Error rescheduling hanging webhook delivery: {}", delivery.getId(), e);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error checking for hanging webhook deliveries", e);
            alertService.sendAlert("Webhook Monitoring Error", 
                    "Error checking for hanging webhook deliveries: " + e.getMessage());
        }
    }

    /**
     * Scheduled task that cleans up old failed webhook deliveries.
     * This prevents the database from growing too large with old delivery records.
     * Runs daily by default.
     */
    @Scheduled(cron = "${scheduler.webhook-cleanup.cron:0 0 2 * * *}")
    @Transactional
    public void cleanupOldDeliveries() {
        try {
            logger.info("Starting webhook delivery cleanup");
            
            LocalDateTime cutoffTime = LocalDateTime.now().minusHours(maxAgeHours);
            
            // Find deliveries that have permanently failed and are older than the cutoff
            List<WebhookDelivery> oldDeliveries = deliveryRepository
                    .findAll()
                    .stream()
                    .filter(d -> d.isTerminalState() && d.getUpdatedAt().isBefore(cutoffTime))
                    .limit(1000) // Limit to prevent massive deletions
                    .toList();
            
            if (!oldDeliveries.isEmpty()) {
                logger.info("Cleaning up {} old webhook deliveries", oldDeliveries.size());
                
                // In a real implementation, you might want to archive these rather than delete
                // deliveryRepository.deleteAll(oldDeliveries);
                
                // Instead, let's mark them with a flag or move to an archive table
                for (WebhookDelivery delivery : oldDeliveries) {
                    // Here we're just logging, but in a real system you might:
                    // 1. Move to an archive table
                    // 2. Add a "archived" flag to the entity and set it
                    // 3. Compress the payload and response data
                    logger.debug("Would archive webhook delivery: {}", delivery.getId());
                }
            } else {
                logger.debug("No old webhook deliveries to clean up");
            }
            
        } catch (Exception e) {
            logger.error("Error during webhook delivery cleanup", e);
            alertService.sendAlert("Webhook Cleanup Error", 
                    "Error during webhook delivery cleanup: " + e.getMessage());
        }
    }

    /**
     * Scheduled task that sends webhook failure reports.
     * This generates a report of webhook endpoints with high failure rates.
     * Runs weekly by default.
     */
    @Scheduled(cron = "${scheduler.webhook-report.cron:0 0 0 * * 0}")
    public void generateWebhookFailureReport() {
        try {
            logger.info("Generating webhook failure report");
            
            // In a real implementation, you would:
            // 1. Query for webhook configurations with high failure rates
            // 2. Generate a report with statistics
            // 3. Send the report via email or other notification channel
            
            // For this example, we'll just log a message
            logger.info("Webhook failure report would be generated here");
            
        } catch (Exception e) {
            logger.error("Error generating webhook failure report", e);
            alertService.sendAlert("Webhook Report Error", 
                    "Error generating webhook failure report: " + e.getMessage());
        }
    }

    /**
     * Manually triggers processing of scheduled retries.
     *
     * @return Number of retries processed
     */
    public int triggerRetryProcessing() {
        if (retryInProgress.compareAndSet(false, true)) {
            try {
                logger.info("Manually triggering webhook retry processing");
                return webhookService.processScheduledRetries();
            } finally {
                retryInProgress.set(false);
            }
        } else {
            throw new IllegalStateException("Retry processing is already in progress");
        }
    }

    /**
     * Enables or disables webhook retries.
     *
     * @param enabled true to enable, false to disable
     */
    public void setRetryEnabled(boolean enabled) {
        this.retryEnabled = enabled;
        logger.info("Webhook retry {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Gets the current retry scheduler status.
     *
     * @return Map containing status information
     */
    public Map<String, Object> getSchedulerStatus() {
        return Map.of(
                "retryInProgress", retryInProgress.get(),
                "retryEnabled", retryEnabled,
                "retryBatchSize", retryBatchSize,
                "maxAgeHours", maxAgeHours,
                "hangTimeoutMinutes", hangTimeoutMinutes
        );
    }
}
