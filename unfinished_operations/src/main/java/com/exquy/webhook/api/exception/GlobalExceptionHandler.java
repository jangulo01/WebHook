package com.exquy.webhook.api.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import javax.validation.ConstraintViolationException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler for the application.
 * Provides consistent error responses across all controllers.
 */
@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Structure for error responses
     */
    private static class ErrorResponse {
        private final LocalDateTime timestamp;
        private final int status;
        private final String error;
        private final String message;
        private final String path;
        private Map<String, Object> details;

        public ErrorResponse(int status, String error, String message, String path) {
            this.timestamp = LocalDateTime.now();
            this.status = status;
            this.error = error;
            this.message = message;
            this.path = path;
        }

        public void setDetails(Map<String, Object> details) {
            this.details = details;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public int getStatus() {
            return status;
        }

        public String getError() {
            return error;
        }

        public String getMessage() {
            return message;
        }

        public String getPath() {
            return path;
        }

        public Map<String, Object> getDetails() {
            return details;
        }
    }

    /**
     * Handles TransactionNotFoundException.
     * This is thrown when a transaction is not found in the system.
     */
    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFoundException(
            TransactionNotFoundException ex, WebRequest request) {
        
        logger.warn("Transaction not found: {}", ex.getMessage());
        
        String path = request.getDescription(false).substring(4); // remove "uri="
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Transaction Not Found",
                ex.getMessage(),
                path
        );
        
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    /**
     * Handles DuplicateTransactionException.
     * This is thrown when a transaction with the same ID already exists but with
     * different payload (violating idempotency).
     */
    @ExceptionHandler(DuplicateTransactionException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateTransactionException(
            DuplicateTransactionException ex, WebRequest request) {
        
        logger.warn("Duplicate transaction detected: {}", ex.getMessage());
        
        String path = request.getDescription(false).substring(4);
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Duplicate Transaction",
                ex.getMessage(),
                path
        );
        
        Map<String, Object> details = new HashMap<>();
        details.put("existingTransactionId", ex.getExistingTransactionId());
        details.put("existingTransactionStatus", ex.getExistingStatus());
        error.setDetails(details);
        
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    /**
     * Handles validation errors from @Valid annotations.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, 
            HttpStatus status, WebRequest request) {
        
        logger.warn("Validation error: {}", ex.getMessage());
        
        Map<String, String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fieldError -> fieldError.getDefaultMessage() != null 
                                ? fieldError.getDefaultMessage() 
                                : "Invalid value"
                ));
        
        String path = request.getDescription(false).substring(4);
        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation Error",
                "Invalid request parameters",
                path
        );
        
        Map<String, Object> details = new HashMap<>();
        details.put("errors", errors);
        error.setDetails(details);
        
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles constraint violation exceptions.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException ex, WebRequest request) {
        
        logger.warn("Constraint violation: {}", ex.getMessage());
        
        Map<String, String> errors = ex.getConstraintViolations()
                .stream()
                .collect(Collectors.toMap(
                        violation -> violation.getPropertyPath().toString(),
                        violation -> violation.getMessage()
                ));
        
        String path = request.getDescription(false).substring(4);
        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation Error",
                "Constraint violation",
                path
        );
        
        Map<String, Object> details = new HashMap<>();
        details.put("errors", errors);
        error.setDetails(details);
        
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles webhook-related exceptions.
     */
    @ExceptionHandler({
            WebhookNotFoundException.class,
            WebhookDeliveryException.class
    })
    public ResponseEntity<ErrorResponse> handleWebhookExceptions(
            Exception ex, WebRequest request) {
        
        logger.warn("Webhook error: {}", ex.getMessage());
        
        HttpStatus status = HttpStatus.NOT_FOUND;
        String errorType = "Webhook Not Found";
        
        if (ex instanceof WebhookDeliveryException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            errorType = "Webhook Delivery Error";
        }
        
        String path = request.getDescription(false).substring(4);
        ErrorResponse error = new ErrorResponse(
                status.value(),
                errorType,
                ex.getMessage(),
                path
        );
        
        return new ResponseEntity<>(error, status);
    }

    /**
     * Fallback handler for all other exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllOtherExceptions(
            Exception ex, WebRequest request) {
        
        logger.error("Unhandled exception occurred", ex);
        
        String path = request.getDescription(false).substring(4);
        ErrorResponse error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "An unexpected error occurred",
                path
        );
        
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Additional exception classes for webhook handling.
     * These would normally be in separate files.
     */
    public static class WebhookNotFoundException extends RuntimeException {
        public WebhookNotFoundException(String message) {
            super(message);
        }
    }

    public static class WebhookDeliveryException extends RuntimeException {
        public WebhookDeliveryException(String message) {
            super(message);
        }
    }
}
