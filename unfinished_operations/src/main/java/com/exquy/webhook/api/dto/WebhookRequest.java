package com.exquy.webhook.api.dto;

import com.company.transactionrecovery.domain.enums.WebhookEventType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.util.Set;

/**
 * Data Transfer Object for webhook registration requests.
 * Contains all the information needed to register or update a webhook.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookRequest {

    /**
     * Identifier of the system registering the webhook.
     */
    @NotBlank(message = "Origin system is required")
    private String originSystem;

    /**
     * URL where webhook notifications will be sent.
     * Must be a valid HTTPS URL.
     */
    @NotBlank(message = "Callback URL is required")
    @Pattern(regexp = "^https://.*", message = "Callback URL must use HTTPS")
    private String callbackUrl;

    /**
     * Set of event types that should trigger webhook notifications.
     */
    @NotEmpty(message = "At least one event type must be specified")
    private Set<WebhookEventType> events;

    /**
     * Secret token used to sign webhook payloads for verification.
     * This secret should be kept secure by both systems.
     */
    @NotBlank(message = "Security token is required")
    private String securityToken;

    /**
     * Flag indicating whether this webhook is active.
     * Defaults to true for new webhooks.
     */
    @Builder.Default
    private boolean active = true;

    /**
     * Maximum number of retry attempts for failed webhook deliveries.
     * If not specified, system default will be used.
     */
    private Integer maxRetries;

    /**
     * Description of the webhook for administrative purposes.
     */
    private String description;

    /**
     * Contact email for notifications about webhook delivery issues.
     */
    @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$", 
             message = "Invalid email format")
    private String contactEmail;
}
