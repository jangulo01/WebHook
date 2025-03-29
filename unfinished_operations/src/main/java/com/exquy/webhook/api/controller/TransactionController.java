package com.exquy.webhook.api.controller;

import com.company.transactionrecovery.api.dto.TransactionRequest;
import com.company.transactionrecovery.api.dto.TransactionResponse;
import com.company.transactionrecovery.domain.enums.TransactionStatus;
import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.domain.model.TransactionHistory;
import com.company.transactionrecovery.domain.service.transaction.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.UUID;

/**
 * REST Controller that handles transaction-related endpoints.
 * Implements the first solution (Inconclusive Operations Protocol) by providing
 * endpoints to create, query, and manage transactions.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;

    @Autowired
    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * Creates a new transaction or retries an existing one.
     * This endpoint is idempotent - sending the same transaction ID multiple times
     * will not create duplicates.
     * 
     * @param request Transaction details
     * @return Transaction response with details and status
     */
    @PostMapping
    public ResponseEntity<TransactionResponse> createTransaction(
            @Valid @RequestBody TransactionRequest request) {
        
        logger.info("Received transaction request with ID: {}", request.getTransactionId());
        
        Transaction transaction = transactionService.processTransaction(request);
        
        TransactionResponse response = TransactionResponse.builder()
                .transactionId(transaction.getId())
                .status(transaction.getStatus())
                .details(transaction.getPayload())
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Gets the current status of a transaction.
     * This is the main endpoint for query verification in the Inconclusive Operations Protocol.
     *
     * @param transactionId Transaction ID to query
     * @return Transaction status and details
     */
    @GetMapping("/{transactionId}/status")
    public ResponseEntity<TransactionResponse> getTransactionStatus(
            @PathVariable UUID transactionId) {
        
        logger.info("Querying status for transaction ID: {}", transactionId);
        
        Transaction transaction = transactionService.getTransaction(transactionId);
        
        TransactionResponse response = TransactionResponse.builder()
                .transactionId(transaction.getId())
                .status(transaction.getStatus())
                .details(transaction.getPayload())
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Retrieves the full transaction details.
     *
     * @param transactionId Transaction ID to retrieve
     * @return Complete transaction information
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable UUID transactionId) {
        
        logger.info("Retrieving transaction with ID: {}", transactionId);
        
        Transaction transaction = transactionService.getTransaction(transactionId);
        
        TransactionResponse response = TransactionResponse.builder()
                .transactionId(transaction.getId())
                .status(transaction.getStatus())
                .details(transaction.getPayload())
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .response(transaction.getResponse())
                .errorDetails(transaction.getErrorDetails())
                .attemptCount(transaction.getAttemptCount())
                .build();
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Gets the state history of a transaction.
     *
     * @param transactionId Transaction ID to retrieve history for
     * @return List of state changes
     */
    @GetMapping("/{transactionId}/history")
    public ResponseEntity<List<TransactionHistory>> getTransactionHistory(
            @PathVariable UUID transactionId) {
        
        logger.info("Retrieving history for transaction ID: {}", transactionId);
        
        List<TransactionHistory> history = transactionService.getTransactionHistory(transactionId);
        
        return new ResponseEntity<>(history, HttpStatus.OK);
    }

    /**
     * For administrative purposes - manually updates the status of a transaction.
     * This should only be used in exceptional cases where automated resolution fails.
     *
     * @param transactionId Transaction ID to update
     * @param status New status to set
     * @param reason Reason for manual status change
     * @return Updated transaction details
     */
    @PutMapping("/{transactionId}/status")
    public ResponseEntity<TransactionResponse> updateTransactionStatus(
            @PathVariable UUID transactionId,
            @RequestParam TransactionStatus status,
            @RequestParam String reason) {
        
        logger.info("Manually updating transaction {} to status {} with reason: {}", 
                transactionId, status, reason);
        
        Transaction transaction = transactionService.updateTransactionStatus(
                transactionId, status, reason, "ADMIN");
        
        TransactionResponse response = TransactionResponse.builder()
                .transactionId(transaction.getId())
                .status(transaction.getStatus())
                .details(transaction.getPayload())
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
