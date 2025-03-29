package com.exquy.webhook.infrastructure.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Client for sending webhook notifications to external systems.
 * Handles the HTTP communication with webhook endpoints.
 */
@Component
public class WebhookClient {

    private static final Logger logger = LoggerFactory.getLogger(WebhookClient.class);

    private final RestTemplate restTemplate;

    @Value("${webhook.connection.timeout-ms:5000}")
    private int connectionTimeout;

    @Value("${webhook.read.timeout-ms:10000}")
    private int readTimeout;

    @Autowired
    public WebhookClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Sends a webhook notification to the specified URL.
     *
     * @param url The URL to send the webhook to
     * @param payload The JSON payload to send
     * @param headers Additional headers to include
     * @return WebhookResponse containing the response status and body
     * @throws Exception if there's an error sending the webhook
     */
    public WebhookResponse sendWebhook(String url, String payload, Map<String, Object> headers) throws Exception {
        logger.debug("Sending webhook to URL: {}", url);
        
        try {
            // Prepare headers
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            
            // Add custom headers
            if (headers != null) {
                headers.forEach((key, value) -> {
                    if (value != null) {
                        httpHeaders.add(key, value.toString());
                    }
                });
            }
            
            // Create the request entity with payload and headers
            HttpEntity<String> entity = new HttpEntity<>(payload, httpHeaders);
            
            // Log request details at trace level (for debugging)
            logger.trace("Webhook request - URL: {}, Headers: {}, Payload: {}", 
                    url, httpHeaders, payload);
            
            // Record start time for metrics
            long startTime = System.nanoTime();
            
            // Send the request
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            
            // Calculate duration for metrics
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            
            // Log success
            logger.debug("Webhook sent successfully to {} in {}ms. Status code: {}", 
                    url, durationMs, response.getStatusCodeValue());
            
            // Create and return webhook response
            return new WebhookResponse(
                    response.getStatusCodeValue(),
                    response.getBody(),
                    durationMs);
            
        } catch (Exception e) {
            logger.error("Error sending webhook to {}: {}", url, e.getMessage());
            throw e;
        }
    }

    /**
     * Tries to send a webhook with retry logic.
     *
     * @param url The URL to send the webhook to
     * @param payload The JSON payload to send
     * @param headers Additional headers to include
     * @param maxRetries Maximum number of retry attempts
     * @param initialDelayMs Initial delay between retries in milliseconds
     * @return WebhookResponse containing the response status and body
     * @throws Exception if all retry attempts fail
     */
    public WebhookResponse sendWebhookWithRetry(
            String url, 
            String payload, 
            Map<String, Object> headers,
            int maxRetries, 
            int initialDelayMs) throws Exception {
        
        Exception lastException = null;
        int retryCount = 0;
        int delayMs = initialDelayMs;
        
        while (retryCount <= maxRetries) {
            try {
                if (retryCount > 0) {
                    logger.info("Retry attempt {} for webhook to {}", retryCount, url);
                }
                
                return sendWebhook(url, payload, headers);
                
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                
                if (retryCount <= maxRetries) {
                    logger.warn("Webhook to {} failed (attempt {}), will retry in {}ms: {}", 
                            url, retryCount, delayMs, e.getMessage());
                    
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Webhook retry interrupted", ie);
                    }
                    
                    // Exponential backoff with jitter
                    delayMs = (int) (delayMs * 1.5 + Math.random() * delayMs * 0.5);
                }
            }
        }
        
        // If we get here, all retries failed
        logger.error("All {} retry attempts failed for webhook to {}", maxRetries, url);
        throw new Exception("Webhook delivery failed after " + maxRetries + " retries", lastException);
    }

    /**
     * Sends a test webhook to verify connectivity.
     *
     * @param url The URL to test
     * @return true if the test was successful, false otherwise
     */
    public boolean testWebhookConnectivity(String url) {
        try {
            String testPayload = "{\"event\":\"test\",\"timestamp\":\"" + 
                    java.time.LocalDateTime.now() + "\"}";
            
            WebhookResponse response = sendWebhook(url, testPayload, null);
            
            // Consider any response in the 2xx range as success
            return response.getStatusCode() >= 200 && response.getStatusCode() < 300;
            
        } catch (Exception e) {
            logger.warn("Webhook connectivity test to {} failed: {}", url, e.getMessage());
            return false;
        }
    }

    /**
     * Internal class representing a webhook response.
     */
    public static class WebhookResponse {
        private final int statusCode;
        private final String body;
        private final long durationMs;

        public WebhookResponse(int statusCode, String body, long durationMs) {
            this.statusCode = statusCode;
            this.body = body;
            this.durationMs = durationMs;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
