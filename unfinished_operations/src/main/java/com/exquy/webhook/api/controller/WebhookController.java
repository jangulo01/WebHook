package com.exquy.webhook.api.controller;

import com.company.transactionrecovery.api.dto.WebhookDeliveryResponse;
import com.company.transactionrecovery.api.dto.WebhookRequest;
import com.company.transactionrecovery.domain.model.WebhookConfig;
import com.company.transactionrecovery.domain.model.WebhookDelivery;
import com.company.transactionrecovery.domain.service.webhook.WebhookRegistrationService;
import com.company.transactionrecovery.domain.service.webhook.WebhookService;
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
 * REST Controller that handles webhook-related endpoints.
 * Implements the second solution (Webhook Notification System) by providing
 * endpoints to register, manage, and test webhooks.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookRegistrationService webhookRegistrationService;
    private final WebhookService webhookService;

    @Autowired
    public WebhookController(WebhookRegistrationService webhookRegistrationService, 
                            WebhookService webhookService) {
        this.webhookRegistrationService = webhookRegistrationService;
        this.webhookService = webhookService;
    }

    /**
     * Registers a new webhook for notifications.
     *
     * @param request Webhook registration details
     * @return Registered webhook configuration
     */
    @PostMapping
    public ResponseEntity<WebhookConfig> registerWebhook(
            @Valid @RequestBody WebhookRequest request) {
        
        logger.info("Registering new webhook for system: {}", request.getOriginSystem());
        
        WebhookConfig webhook = webhookRegistrationService.registerWebhook(
                request.getOriginSystem(), 
                request.getCallbackUrl(), 
                request.getEvents(), 
                request.getSecurityToken());
        
        return new ResponseEntity<>(webhook, HttpStatus.CREATED);
    }

    /**
     * Updates an existing webhook configuration.
     *
     * @param webhookId Webhook ID to update
     * @param request Updated webhook details
     * @return Updated webhook configuration
     */
    @PutMapping("/{webhookId}")
    public ResponseEntity<WebhookConfig> updateWebhook(
            @PathVariable UUID webhookId,
            @Valid @RequestBody WebhookRequest request) {
        
        logger.info("Updating webhook configuration for ID: {}", webhookId);
        
        WebhookConfig webhook = webhookRegistrationService.updateWebhook(
                webhookId,
                request.getCallbackUrl(), 
                request.getEvents(), 
                request.getSecurityToken(),
                request.isActive());
        
        return new ResponseEntity<>(webhook, HttpStatus.OK);
    }

    /**
     * Deletes a webhook configuration.
     *
     * @param webhookId Webhook ID to delete
     * @return Response with no content
     */
    @DeleteMapping("/{webhookId}")
    public ResponseEntity<Void> deleteWebhook(@PathVariable UUID webhookId) {
        logger.info("Deleting webhook with ID: {}", webhookId);
        
        webhookRegistrationService.deleteWebhook(webhookId);
        
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    /**
     * Gets all webhook configurations for a specific origin system.
     *
     * @param originSystem The origin system identifier
     * @return List of webhook configurations
     */
    @GetMapping
    public ResponseEntity<List<WebhookConfig>> getWebhooksByOriginSystem(
            @RequestParam String originSystem) {
        
        logger.info("Retrieving webhooks for origin system: {}", originSystem);
        
        List<WebhookConfig> webhooks = webhookRegistrationService.getWebhooksByOriginSystem(originSystem);
        
        return new ResponseEntity<>(webhooks, HttpStatus.OK);
    }

    /**
     * Tests a webhook by sending a test event.
     *
     * @param webhookId Webhook ID to test
     * @return Delivery status of the test event
     */
    @PostMapping("/{webhookId}/test")
    public ResponseEntity<WebhookDeliveryResponse> testWebhook(@PathVariable UUID webhookId) {
        logger.info("Testing webhook with ID: {}", webhookId);
        
        WebhookDelivery delivery = webhookService.sendTestEvent(webhookId);
        
        WebhookDeliveryResponse response = WebhookDeliveryResponse.builder()
                .deliveryId(delivery.getId())
                .webhookId(delivery.getWebhookId())
                .eventType(delivery.getEventType())
                .status(delivery.getDeliveryStatus())
                .attemptCount(delivery.getAttemptCount())
                .lastAttemptAt(delivery.getLastAttemptAt())
                .responseCode(delivery.getResponseCode())
                .build();
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Acknowledges receipt of a webhook event.
     * This endpoint is called by the origin system to confirm processing of an event.
     *
     * @param eventId ID of the webhook event
     * @param status Processing status
     * @return Response with no content
     */
    @PostMapping("/acknowledge")
    public ResponseEntity<Void> acknowledgeEvent(
            @RequestParam UUID eventId,
            @RequestParam String status) {
        
        logger.info("Acknowledging webhook event: {} with status: {}", eventId, status);
        
        webhookService.acknowledgeDelivery(eventId, status);
        
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    /**
     * Gets the delivery history for a specific webhook.
     *
     * @param webhookId Webhook ID
     * @return List of delivery attempts
     */
    @GetMapping("/{webhookId}/deliveries")
    public ResponseEntity<List<WebhookDeliveryResponse>> getWebhookDeliveries(
            @PathVariable UUID webhookId) {
        
        logger.info("Retrieving delivery history for webhook ID: {}", webhookId);
        
        List<WebhookDelivery> deliveries = webhookService.getDeliveriesByWebhookId(webhookId);
        
        List<WebhookDeliveryResponse> response = deliveries.stream()
                .map(delivery -> WebhookDeliveryResponse.builder()
                        .deliveryId(delivery.getId())
                        .webhookId(delivery.getWebhookId())
                        .transactionId(delivery.getTransactionId())
                        .eventType(delivery.getEventType())
                        .status(delivery.getDeliveryStatus())
                        .attemptCount(delivery.getAttemptCount())
                        .lastAttemptAt(delivery.getLastAttemptAt())
                        .responseCode(delivery.getResponseCode())
                        .build())
                .toList();
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Manually triggers a retry for a failed webhook delivery.
     *
     * @param deliveryId Delivery ID to retry
     * @return Updated delivery status
     */
    @PostMapping("/deliveries/{deliveryId}/retry")
    public ResponseEntity<WebhookDeliveryResponse> retryDelivery(
            @PathVariable UUID deliveryId) {
        
        logger.info("Manually retrying webhook delivery: {}", deliveryId);
        
        WebhookDelivery delivery = webhookService.retryDelivery(deliveryId);
        
        WebhookDeliveryResponse response = WebhookDeliveryResponse.builder()
                .deliveryId(delivery.getId())
                .webhookId(delivery.getWebhookId())
                .eventType(delivery.getEventType())
                .status(delivery.getDeliveryStatus())
                .attemptCount(delivery.getAttemptCount())
                .lastAttemptAt(delivery.getLastAttemptAt())
                .responseCode(delivery.getResponseCode())
                .build();
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
