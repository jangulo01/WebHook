package com.exquy.webhook.service.transaction;

import com.company.transactionrecovery.api.dto.TransactionRequest;
import com.company.transactionrecovery.domain.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Service responsible for ensuring idempotency in transaction processing.
 * This prevents duplicate transactions from being processed while allowing
 * legitimate retries of the same transaction.
 */
@Service
public class IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyService.class);

    /**
     * Fields that are considered critical for idempotency checking.
     * Changes to these fields indicate a different transaction rather than a retry.
     */
    @Value("${idempotency.critical-fields:amount,accountNumber,description,reference}")
    private Set<String> criticalFields;

    /**
     * Fields that should be ignored when comparing transactions.
     * These fields might change between retries without indicating a different transaction.
     */
    @Value("${idempotency.ignored-fields:timestamp,clientIp,deviceId}")
    private Set<String> ignoredFields;

    /**
     * Threshold for payload similarity (as a percentage).
     * Below this threshold, transactions are considered different.
     */
    @Value("${idempotency.similarity-threshold:80}")
    private int similarityThreshold;

    /**
     * Checks if a transaction request is idempotent with an existing transaction.
     * 
     * @param existingTransaction The existing transaction in the system
     * @param request The incoming transaction request
     * @return true if the request is idempotent (same transaction), false otherwise
     */
    public boolean isIdempotent(Transaction existingTransaction, TransactionRequest request) {
        // If origin systems don't match, definitely not idempotent
        if (!Objects.equals(existingTransaction.getOriginSystem(), request.getOriginSystem())) {
            logger.warn("Idempotency check failed: origin system mismatch for transaction ID: {}", 
                    request.getTransactionId());
            return false;
        }

        // Check payload for critical differences
        Map<String, Object> existingPayload = existingTransaction.getPayload();
        Map<String, Object> newPayload = request.getPayload();

        // If payload is null in either, compare the other fields
        if (existingPayload == null || newPayload == null) {
            logger.warn("One of the payloads is null for transaction ID: {}", request.getTransactionId());
            return existingPayload == newPayload; // Both null = idempotent, one null = not idempotent
        }

        // Check critical fields first
        for (String field : criticalFields) {
            if (!isFieldValueEqual(existingPayload, newPayload, field)) {
                logger.warn("Idempotency check failed: critical field '{}' differs for transaction ID: {}", 
                        field, request.getTransactionId());
                return false;
            }
        }

        // Calculate overall similarity for non-critical fields
        int totalFields = 0;
        int matchingFields = 0;

        for (String key : existingPayload.keySet()) {
            // Skip ignored fields
            if (ignoredFields.contains(key)) {
                continue;
            }
            
            // Skip critical fields (already checked)
            if (criticalFields.contains(key)) {
                continue;
            }

            totalFields++;
            if (isFieldValueEqual(existingPayload, newPayload, key)) {
                matchingFields++;
            }
        }

        // Check for completely new fields in the request
        for (String key : newPayload.keySet()) {
            if (!existingPayload.containsKey(key) && !ignoredFields.contains(key)) {
                totalFields++;
            }
        }

        // Calculate similarity percentage
        int similarityPercentage = totalFields > 0 
                ? (matchingFields * 100) / totalFields 
                : 100; // If no fields to compare, consider them equal

        boolean isIdempotent = similarityPercentage >= similarityThreshold;

        if (!isIdempotent) {
            logger.warn("Idempotency check failed: similarity {}% is below threshold {}% for transaction ID: {}", 
                    similarityPercentage, similarityThreshold, request.getTransactionId());
        }

        return isIdempotent;
    }

    /**
     * Compares the value of a specific field in two payloads.
     *
     * @param payload1 First payload
     * @param payload2 Second payload
     * @param fieldName Name of the field to compare
     * @return true if the field values are equal, false otherwise
     */
    private boolean isFieldValueEqual(Map<String, Object> payload1, Map<String, Object> payload2, String fieldName) {
        Object value1 = payload1.get(fieldName);
        Object value2 = payload2.get(fieldName);

        // Handle nested fields (e.g., "details.amount")
        if (fieldName.contains(".")) {
            String[] parts = fieldName.split("\\.", 2);
            Object subObj1 = payload1.get(parts[0]);
            Object subObj2 = payload2.get(parts[0]);

            if (subObj1 instanceof Map && subObj2 instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> subMap1 = (Map<String, Object>) subObj1;
                @SuppressWarnings("unchecked")
                Map<String, Object> subMap2 = (Map<String, Object>) subObj2;
                
                return isFieldValueEqual(subMap1, subMap2, parts[1]);
            }
            return Objects.equals(subObj1, subObj2);
        }

        // Special handling for numeric types
        if (value1 instanceof Number && value2 instanceof Number) {
            // Convert to double for comparison to handle different numeric types
            double num1 = ((Number) value1).doubleValue();
            double num2 = ((Number) value2).doubleValue();
            
            // Use a small epsilon for floating point comparison
            return Math.abs(num1 - num2) < 0.0001;
        }

        // Default comparison
        return Objects.equals(value1, value2);
    }

    /**
     * Updates the set of critical fields used for idempotency checking.
     *
     * @param criticalFields The new set of critical fields
     */
    public void setCriticalFields(Set<String> criticalFields) {
        this.criticalFields = criticalFields;
    }

    /**
     * Updates the set of ignored fields used for idempotency checking.
     *
     * @param ignoredFields The new set of ignored fields
     */
    public void setIgnoredFields(Set<String> ignoredFields) {
        this.ignoredFields = ignoredFields;
    }

    /**
     * Updates the similarity threshold.
     *
     * @param similarityThreshold The new similarity threshold
     */
    public void setSimilarityThreshold(int similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }
}
