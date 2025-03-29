package com.exquy.webhook.service.monitor;

import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.domain.model.TransactionHistory;
import com.company.transactionrecovery.domain.repository.TransactionHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Service responsible for sending alerts about system issues.
 * Handles notifications for critical events that require attention,
 * such as anomalous transactions or system errors.
 */
@Service
public class AlertService {

    private static final Logger logger = LoggerFactory.getLogger(AlertService.class);

    private final JavaMailSender emailSender;
    private final TransactionHistoryRepository historyRepository;
    private final AnomalyDetectionService anomalyDetectionService;

    @Value("${alert.email.enabled:false}")
    private boolean emailAlertsEnabled;

    @Value("${alert.email.recipients}")
    private String[] alertRecipients;

    @Value("${alert.email.from}")
    private String alertFromAddress;

    @Value("${application.base-url}")
    private String applicationBaseUrl;

    @Autowired
    public AlertService(
            JavaMailSender emailSender,
            TransactionHistoryRepository historyRepository,
            AnomalyDetectionService anomalyDetectionService) {
        this.emailSender = emailSender;
        this.historyRepository = historyRepository;
        this.anomalyDetectionService = anomalyDetectionService;
    }

    /**
     * Sends a generic alert.
     *
     * @param subject The alert subject
     * @param message The alert message
     */
    @Async
    public void sendAlert(String subject, String message) {
        logger.info("Sending alert: {}", subject);
        
        // Log the alert
        logger.warn("SYSTEM ALERT - {}: {}", subject, message);
        
        // Send email if enabled
        if (emailAlertsEnabled) {
            try {
                SimpleMailMessage email = new SimpleMailMessage();
                email.setFrom(alertFromAddress);
                email.setTo(alertRecipients);
                email.setSubject("ALERT: " + subject);
                email.setText(message);
                
                emailSender.send(email);
                
                logger.debug("Email alert sent to {}", String.join(", ", alertRecipients));
            } catch (Exception e) {
                logger.error("Failed to send email alert", e);
            }
        }
        
        // Additional alert channels could be implemented here
        // Such as SMS, chat notifications, etc.
    }

    /**
     * Sends an alert for an anomalous transaction.
     *
     * @param transaction The transaction to send an alert for
     */
    @Async
    public void sendTransactionAlert(Transaction transaction) {
        String transactionId = transaction.getId().toString();
        logger.info("Sending transaction alert for: {}", transactionId);
        
        // Analyze transaction anomalies
        Map<String, String> anomalies = anomalyDetectionService.analyzeTransaction(transaction);
        
        if (anomalies.isEmpty()) {
            logger.debug("No anomalies found for transaction: {}", transactionId);
            return;
        }
        
        // Get transaction history
        List<TransactionHistory> history = historyRepository
                .findByTransactionIdOrderByChangedAtAsc(transaction.getId());
        
        // Build alert message
        StringBuilder message = new StringBuilder();
        message.append("Transaction requiring attention: ").append(transactionId).append("\n\n");
        message.append("Current Status: ").append(transaction.getStatus()).append("\n");
        message.append("Origin System: ").append(transaction.getOriginSystem()).append("\n");
        message.append("Created At: ").append(formatDateTime(transaction.getCreatedAt())).append("\n");
        message.append("Last Updated: ").append(formatDateTime(transaction.getUpdatedAt())).append("\n");
        message.append("Attempt Count: ").append(transaction.getAttemptCount()).append("\n\n");
        
        message.append("Detected Anomalies:\n");
        for (Map.Entry<String, String> anomaly : anomalies.entrySet()) {
            message.append("- ").append(anomaly.getValue()).append("\n");
        }
        
        message.append("\nTransaction History:\n");
        for (TransactionHistory entry : history) {
            message.append("- ").append(formatDateTime(entry.getChangedAt()))
                   .append(": ");
            
            if (entry.getPreviousStatus() != null) {
                message.append(entry.getPreviousStatus()).append(" -> ");
            }
            
            message.append(entry.getNewStatus());
            
            if (entry.getReason() != null && !entry.getReason().isEmpty()) {
                message.append(" (").append(entry.getReason()).append(")");
            }
            
            message.append("\n");
        }
        
        message.append("\nManagement Interface Link: ")
               .append(applicationBaseUrl)
               .append("/admin/transactions/")
               .append(transactionId)
               .append("\n");
        
        // Send the alert
        sendAlert("Anomalous Transaction " + transactionId, message.toString());
    }

    /**
     * Sends a system health alert.
     *
     * @param metrics System metrics
     * @param anomalyStats Anomaly statistics
     */
    @Async
    public void sendSystemHealthAlert(Map<String, Object> metrics, Map<String, Object> anomalyStats) {
        logger.info("Sending system health alert");
        
        StringBuilder message = new StringBuilder();
        message.append("System Health Alert\n\n");
        
        // Add metrics summary
        message.append("System Metrics:\n");
        
        @SuppressWarnings("unchecked")
        Map<String, Long> countsByStatus = (Map<String, Long>) metrics.getOrDefault(
                "transactions_by_status", Map.of());
                
        for (Map.Entry<String, Long> entry : countsByStatus.entrySet()) {
            message.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        
        message.append("- Completion Rate: ").append(metrics.getOrDefault("completion_rate", "N/A")).append("%\n");
        message.append("- Failure Rate: ").append(metrics.getOrDefault("failure_rate", "N/A")).append("%\n\n");
        
        // Add anomaly statistics
        message.append("Anomaly Statistics:\n");
        message.append("- Total Anomalies: ").append(anomalyStats.getOrDefault("total_anomalies", "0")).append("\n");
        
        @SuppressWarnings("unchecked")
        Map<String, Integer> anomalyTypes = (Map<String, Integer>) anomalyStats.getOrDefault(
                "anomaly_types", Map.of());
                
        for (Map.Entry<String, Integer> entry : anomalyTypes.entrySet()) {
            message.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        
        message.append("\nManagement Interface Link: ")
               .append(applicationBaseUrl)
               .append("/admin/dashboard\n");
        
        // Send the alert
        sendAlert("System Health Status", message.toString());
    }

    /**
     * Sends an alert for a critical system error.
     *
     * @param error The error that occurred
     * @param details Additional details about the error
     */
    @Async
    public void sendCriticalErrorAlert(Throwable error, String details) {
        logger.info("Sending critical error alert");
        
        StringBuilder message = new StringBuilder();
        message.append("Critical System Error\n\n");
        message.append("Error: ").append(error.getClass().getName()).append("\n");
        message.append("Message: ").append(error.getMessage()).append("\n\n");
        
        if (details != null && !details.isEmpty()) {
            message.append("Details: ").append(details).append("\n\n");
        }
        
        // Add stack trace
        message.append("Stack Trace:\n");
        for (StackTraceElement element : error.getStackTrace()) {
            message.append("  at ").append(element.toString()).append("\n");
            // Limit stack trace to 20 lines to avoid massive emails
            if (message.toString().split("\n").length > 30) {
                message.append("  ... (stack trace truncated)\n");
                break;
            }
        }
        
        message.append("\nThis error requires immediate attention!\n");
        
        // Send the alert with high priority marking
        sendAlert("[CRITICAL] System Error", message.toString());
    }

    /**
     * Formats a date-time for display in alerts.
     *
     * @param dateTime The date-time to format
     * @return The formatted date-time string
     */
    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "N/A";
        }
        
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * Checks if email alerts are enabled.
     *
     * @return true if email alerts are enabled, false otherwise
     */
    public boolean isEmailAlertsEnabled() {
        return emailAlertsEnabled;
    }

    /**
     * Sets whether email alerts are enabled.
     *
     * @param emailAlertsEnabled true to enable email alerts, false to disable
     */
    public void setEmailAlertsEnabled(boolean emailAlertsEnabled) {
        this.emailAlertsEnabled = emailAlertsEnabled;
    }
}
