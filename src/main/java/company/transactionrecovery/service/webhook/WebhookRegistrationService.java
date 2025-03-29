package com.company.transactionrecovery.domain.service.webhook;

import com.company.transactionrecovery.api.exception.GlobalExceptionHandler.WebhookNotFoundException;
import com.company.transactionrecovery.domain.enums.WebhookEventType;
import com.company.transactionrecovery.domain.model.WebhookConfig;
import com.company.transactionrecovery.domain.repository.WebhookConfigRepository;
import com.company.transactionrecovery.util.SignatureUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Service responsible for registering and managing webhook configurations.
 * Provides methods for creating, updating, and deleting webhook registrations.
 */
@Service
public class WebhookRegistrationService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookRegistrationService.class);

    private final WebhookConfigRepository webhookConfigRepository;
    private final WebhookSecurityService securityService;

    @Value("${webhook.default-max-retries:5}")
    private int defaultMaxRetries;

    /**
     * Pattern for validating HTTPS URLs
     */
    private static final Pattern HTTPS_URL_PATTERN = 
            Pattern.compile("^https://[\\w.-]+(:\\d+)?(/[\\w-./?%&=]*)?$");

    @Autowired
    public WebhookRegistrationService(
            WebhookConfigRepository webhookConfigRepository,
            WebhookSecurityService securityService) {
        this.webhookConfigRepository = webhookConfigRepository;
        this.securityService = securityService;
    }

    /**
     * Registers a new webhook configuration.
     *
     * @param originSystem The origin system identifier
     * @param callbackUrl The URL where webhook notifications will be sent
     * @param events The set of event types to subscribe to
     * @param securityToken The token used for signing webhook payloads
     * @return The created webhook configuration
     */
    @Transactional
    public WebhookConfig registerWebhook(
            String originSystem,
            String callbackUrl,
            Set<WebhookEventType> events,
            String securityToken) {
        
        logger.info("Registering new webhook for system: {}, URL: {}", originSystem, callbackUrl);
        
        validateWebhookUrl(callbackUrl);
        
        // Check if a webhook with this URL already exists for this origin system
        webhookConfigRepository.findByCallbackUrl(callbackUrl)
                .ifPresent(existing -> {
                    if (existing.getOriginSystem().equals(originSystem)) {
                        logger.warn("Webhook already exists with URL: {}", callbackUrl);
                        throw new IllegalArgumentException("Webhook already registered with this URL");
                    }
                });
        
        // Hash the security token before storing
        String hashedToken = securityService.hashSecurityToken(securityToken);
        
        WebhookConfig config = WebhookConfig.builder()
                .originSystem(originSystem)
                .callbackUrl(callbackUrl)
                .events(events)
                .securityToken(hashedToken)
                .isActive(true)
                .maxRetries(defaultMaxRetries)
                .build();
        
        return webhookConfigRepository.save(config);
    }

    /**
     * Updates an existing webhook configuration.
     *
     * @param webhookId The webhook ID
     * @param callbackUrl The new callback URL (optional, pass null to keep existing)
     * @param events The new set of events (optional, pass null to keep existing)
     * @param securityToken The new security token (optional, pass null to keep existing)
     * @param isActive The new active status (optional, pass null to keep existing)
     * @return The updated webhook configuration
     */
    @Transactional
    public WebhookConfig updateWebhook(
            UUID webhookId,
            String callbackUrl,
            Set<WebhookEventType> events,
            String securityToken,
            Boolean isActive) {
        
        WebhookConfig config = webhookConfigRepository.findById(webhookId)
                .orElseThrow(() -> new WebhookNotFoundException("Webhook not found with ID: " + webhookId));
        
        logger.info("Updating webhook: {}, origin system: {}", webhookId, config.getOriginSystem());
        
        if (callbackUrl != null) {
            validateWebhookUrl(callbackUrl);
            config.setCallbackUrl(callbackUrl);
        }
        
        if (events != null) {
            config.setEvents(events);
        }
        
        if (securityToken != null) {
            String hashedToken = securityService.hashSecurityToken(securityToken);
            config.setSecurityToken(hashedToken);
        }
        
        if (isActive != null) {
            config.setIsActive(isActive);
        }
        
        config.setUpdatedAt(LocalDateTime.now());
        
        return webhookConfigRepository.save(config);
    }

    /**
     * Deletes a webhook configuration.
     *
     * @param webhookId The webhook ID
     */
    @Transactional
    public void deleteWebhook(UUID webhookId) {
        if (!webhookConfigRepository.existsById(webhookId)) {
            throw new WebhookNotFoundException("Webhook not found with ID: " + webhookId);
        }
        
        logger.info("Deleting webhook: {}", webhookId);
        webhookConfigRepository.deleteById(webhookId);
    }

    /**
     * Gets all webhook configurations for a specific origin system.
     *
     * @param originSystem The origin system identifier
     * @return List of webhook configurations
     */
    @Transactional(readOnly = true)
    public List<WebhookConfig> getWebhooksByOriginSystem(String originSystem) {
        logger.debug("Getting webhooks for origin system: {}", originSystem);
        return webhookConfigRepository.findByOriginSystem(originSystem);
    }

    /**
     * Gets all active webhook configurations for a specific origin system.
     *
     * @param originSystem The origin system identifier
     * @return List of active webhook configurations
     */
    @Transactional(readOnly = true)
    public List<WebhookConfig> getActiveWebhooksByOriginSystem(String originSystem) {
        logger.debug("Getting active webhooks for origin system: {}", originSystem);
        return webhookConfigRepository.findByOriginSystemAndIsActiveTrue(originSystem);
    }

    /**
     * Gets a webhook configuration by its ID.
     *
     * @param webhookId The webhook ID
     * @return The webhook configuration
     */
    @Transactional(readOnly = true)
    public WebhookConfig getWebhookById(UUID webhookId) {
        return webhookConfigRepository.findById(webhookId)
                .orElseThrow(() -> new WebhookNotFoundException("Webhook not found with ID: " + webhookId));
    }

    /**
     * Sets the maximum number of retry attempts for a webhook.
     *
     * @param webhookId The webhook ID
     * @param maxRetries The maximum number of retry attempts
     * @return The updated webhook configuration
     */
    @Transactional
    public WebhookConfig setMaxRetries(UUID webhookId, int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries cannot be negative");
        }
        
        WebhookConfig config = getWebhookById(webhookId);
        config.setMaxRetries(maxRetries);
        
        return webhookConfigRepository.save(config);
    }

    /**
     * Updates the contact email for a webhook.
     *
     * @param webhookId The webhook ID
     * @param contactEmail The contact email
     * @return The updated webhook configuration
     */
    @Transactional
    public WebhookConfig setContactEmail(UUID webhookId, String contactEmail) {
        WebhookConfig config = getWebhookById(webhookId);
        config.setContactEmail(contactEmail);
        
        return webhookConfigRepository.save(config);
    }

    /**
     * Updates the description for a webhook.
     *
     * @param webhookId The webhook ID
     * @param description The description
     * @return The updated webhook configuration
     */
    @Transactional
    public WebhookConfig setDescription(UUID webhookId, String description) {
        WebhookConfig config = getWebhookById(webhookId);
        config.setDescription(description);
        
        return webhookConfigRepository.save(config);
    }

    /**
     * Validates a webhook URL.
     * 
     * @param url The URL to validate
     * @throws IllegalArgumentException if the URL is invalid
     */
    private void validateWebhookUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("Webhook URL cannot be empty");
        }
        
        if (!HTTPS_URL_PATTERN.matcher(url).matches()) {
            throw new IllegalArgumentException(
                    "Webhook URL must be a valid HTTPS URL. Invalid URL: " + url);
        }
        
        // Additional security checks could be added here
        if (url.contains("localhost") || url.contains("127.0.0.1")) {
            throw new IllegalArgumentException("Webhook URL cannot point to localhost");
        }
    }

    /**
     * Verifies if a webhook is active.
     *
     * @param webhookId The webhook ID
     * @return true if the webhook is active, false otherwise
     */
    @Transactional(readOnly = true)
    public boolean isWebhookActive(UUID webhookId) {
        WebhookConfig config = getWebhookById(webhookId);
        return config.getIsActive();
    }

    /**
     * Activates or deactivates a webhook.
     *
     * @param webhookId The webhook ID
     * @param active Whether the webhook should be active
     * @return The updated webhook configuration
     */
    @Transactional
    public WebhookConfig setWebhookActive(UUID webhookId, boolean active) {
        WebhookConfig config = getWebhookById(webhookId);
        config.setIsActive(active);
        
        logger.info("{} webhook: {}", active ? "Activating" : "Deactivating", webhookId);
        
        return webhookConfigRepository.save(config);
    }
}
