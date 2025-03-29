package com.exquy.webhook.api.controller;

import com.exquy.webhook.api.dto.TransactionResponse;
import com.exquy.webhook.domain.model.Transaction;
import com.exquy.webhook.service.monitor.AnomalyDetectionService;
import com.exquy.webhook.service.monitor.TransactionMonitorService;
import com.exquy.webhook.service.transaction.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller that provides administrative functions for the system.
 * These endpoints are secured and should only be accessible to administrators.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);

    private final com.exquy.webhook.service.transaction.TransactionService transactionService;
    private final com.exquy.webhook.service.monitor.TransactionMonitorService monitorService;
    private final com.exquy.webhook.service.monitor.AnomalyDetectionService anomalyDetectionService;

    @Autowired
    public AdminController(com.exquy.webhook.service.transaction.TransactionService transactionService,
                           com.exquy.webhook.service.monitor.TransactionMonitorService monitorService,
                           com.exquy.webhook.service.monitor.AnomalyDetectionService anomalyDetectionService) {
        this.transactionService = transactionService;
        this.monitorService = monitorService;
        this.anomalyDetectionService = anomalyDetectionService;
    }

    /**
     * Searches for transactions based on various criteria.
     *
     * @param originSystem Origin system identifier (optional)
     * @param status Transaction status (optional)
     * @param startDate Start date for date range (optional)
     * @param endDate End date for date range (optional)
     * @param pageable Pagination information
     * @return Paged list of transactions matching criteria
     */
    @GetMapping("/transactions/search")
    public ResponseEntity<Page<com.exquy.webhook.api.dto.TransactionResponse>> searchTransactions(
            @RequestParam(required = false) String originSystem,
            @RequestParam(required = false) org.springframework.transaction.TransactionStatus status,
            @RequestParam(required = false) LocalDateTime startDate,
            @RequestParam(required = false) LocalDateTime endDate,
            Pageable pageable) {
        
        logger.info("Admin searching transactions with filters: originSystem={}, status={}, date range: {} to {}",
                originSystem, status, startDate, endDate);
        
        Page<com.exquy.webhook.domain.model.Transaction> transactions = transactionService.searchTransactions(
                originSystem, status, startDate, endDate, pageable);
        
        Page<com.exquy.webhook.api.dto.TransactionResponse> response = transactions.map(transaction ->
                com.exquy.webhook.api.dto.TransactionResponse.builder()
                        .transactionId(transaction.getId())
                        .status(transaction.getStatus())
                        .details(transaction.getPayload())
                        .createdAt(transaction.getCreatedAt())
                        .updatedAt(transaction.getUpdatedAt())
                        .attemptCount(transaction.getAttemptCount())
                        .build());
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Gets a dashboard of current system metrics.
     *
     * @return Map of metrics and their values
     */
    @GetMapping("/dashboard/metrics")
    public ResponseEntity<Map<String, Object>> getDashboardMetrics() {
        logger.info("Admin requesting dashboard metrics");
        
        Map<String, Object> metrics = monitorService.getSystemMetrics();
        
        return new ResponseEntity<>(metrics, HttpStatus.OK);
    }

    /**
     * Gets a list of transactions that are currently in an anomalous state.
     *
     * @return List of anomalous transactions
     */
    @GetMapping("/transactions/anomalies")
    public ResponseEntity<List<com.exquy.webhook.api.dto.TransactionResponse>> getAnomalousTransactions() {
        logger.info("Admin requesting anomalous transactions");
        
        List<com.exquy.webhook.domain.model.Transaction> anomalies = anomalyDetectionService.detectAnomalousTransactions();
        
        List<com.exquy.webhook.api.dto.TransactionResponse> response = anomalies.stream()
                .map(transaction -> com.exquy.webhook.api.dto.TransactionResponse.builder()
                        .transactionId(transaction.getId())
                        .status(transaction.getStatus())
                        .details(transaction.getPayload())
                        .createdAt(transaction.getCreatedAt())
                        .updatedAt(transaction.getUpdatedAt())
                        .attemptCount(transaction.getAttemptCount())
                        .errorDetails(transaction.getErrorDetails())
                        .build())
                .toList();
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Manually triggers the transaction monitor process.
     *
     * @return Summary of actions taken
     */
    @PostMapping("/monitor/run")
    public ResponseEntity<Map<String, Object>> triggerMonitor() {
        logger.info("Admin manually triggering transaction monitor");
        
        Map<String, Object> result = monitorService.manuallyRunMonitor();
        
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /**
     * Manually resolves a transaction that is in an inconsistent state.
     *
     * @param transactionId Transaction ID to resolve
     * @param targetStatus Target status to set
     * @param reason Reason for manual resolution
     * @return Updated transaction
     */
    @PostMapping("/transactions/{transactionId}/resolve")
    public ResponseEntity<com.exquy.webhook.api.dto.TransactionResponse> resolveTransaction(
            @PathVariable UUID transactionId,
            @RequestParam org.springframework.transaction.TransactionStatus targetStatus,
            @RequestParam String reason) {
        
        logger.info("Admin manually resolving transaction {} to status {} with reason: {}",
                transactionId, targetStatus, reason);
        
        com.exquy.webhook.domain.model.Transaction transaction = transactionService.updateTransactionStatus(
                transactionId, targetStatus, reason, "ADMIN_MANUAL");
        
        com.exquy.webhook.api.dto.TransactionResponse response = com.exquy.webhook.api.dto.TransactionResponse.builder()
                .transactionId(transaction.getId())
                .status(transaction.getStatus())
                .details(transaction.getPayload())
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Gets system statistics for a specific time period.
     *
     * @param startDate Start of time period
     * @param endDate End of time period
     * @return Map of statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getSystemStats(
            @RequestParam LocalDateTime startDate,
            @RequestParam LocalDateTime endDate) {
        
        logger.info("Admin requesting system statistics from {} to {}", startDate, endDate);
        
        Map<String, Object> stats = monitorService.getSystemStatistics(startDate, endDate);
        
        return new ResponseEntity<>(stats, HttpStatus.OK);
    }

    /**
     * Runs a reconciliation process to detect and fix inconsistencies.
     * This is typically used after system failures or maintenance.
     *
     * @return Results of the reconciliation process
     */
    @PostMapping("/reconciliation")
    public ResponseEntity<Map<String, Object>> runReconciliation() {
        logger.info("Admin initiating reconciliation process");
        
        Map<String, Object> result = monitorService.runReconciliationProcess();
        
        return new ResponseEntity<>(result, HttpStatus.OK);
    }
}
