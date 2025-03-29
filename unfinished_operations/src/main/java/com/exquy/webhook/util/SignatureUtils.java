package com.exquy.webhook.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

/**
 * Utility class for cryptographic signature operations.
 * Provides methods for generating and verifying signatures for webhook payloads
 * and other security-related functionality.
 */
@Component
public class SignatureUtils {

    private static final Logger logger = LoggerFactory.getLogger(SignatureUtils.class);

    @Value("${webhook.signature.algorithm:HmacSHA256}")
    private String defaultSignatureAlgorithm;

    /**
     * Generates an HMAC signature for a payload using the provided secret key.
     *
     * @param payload The payload to sign
     * @param secretKey The secret key to use for signing
     * @return The Base64-encoded signature
     * @throws IllegalStateException if signature generation fails
     */
    public String generateHmacSignature(String payload, String secretKey) {
        return generateHmacSignature(payload, secretKey, defaultSignatureAlgorithm);
    }

    /**
     * Generates an HMAC signature for a payload using the provided secret key and algorithm.
     *
     * @param payload The payload to sign
     * @param secretKey The secret key to use for signing
     * @param algorithm The HMAC algorithm to use (e.g., HmacSHA256, HmacSHA512)
     * @return The Base64-encoded signature
     * @throws IllegalStateException if signature generation fails
     */
    public String generateHmacSignature(String payload, String secretKey, String algorithm) {
        try {
            Mac hmac = Mac.getInstance(algorithm);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), algorithm);
            
            hmac.init(keySpec);
            byte[] signatureBytes = hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            
            return Base64.getEncoder().encodeToString(signatureBytes);
            
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.error("Failed to generate HMAC signature: {}", e.getMessage());
            throw new IllegalStateException("Signature generation failed", e);
        }
    }

    /**
     * Verifies an HMAC signature for a payload.
     *
     * @param payload The payload that was signed
     * @param signature The signature to verify
     * @param secretKey The secret key used for signing
     * @return true if the signature is valid, false otherwise
     */
    public boolean verifyHmacSignature(String payload, String signature, String secretKey) {
        return verifyHmacSignature(payload, signature, secretKey, defaultSignatureAlgorithm);
    }

    /**
     * Verifies an HMAC signature for a payload using the specified algorithm.
     *
     * @param payload The payload that was signed
     * @param signature The signature to verify
     * @param secretKey The secret key used for signing
     * @param algorithm The HMAC algorithm used (e.g., HmacSHA256, HmacSHA512)
     * @return true if the signature is valid, false otherwise
     */
    public boolean verifyHmacSignature(String payload, String signature, String secretKey, String algorithm) {
        try {
            String expectedSignature = generateHmacSignature(payload, secretKey, algorithm);
            return constantTimeEquals(expectedSignature, signature);
        } catch (Exception e) {
            logger.error("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Generates a webhook signing key.
     * This creates a secure random string suitable for use as a webhook secret.
     *
     * @return A secure random string
     */
    public String generateWebhookSigningKey() {
        byte[] randomBytes = new byte[32]; // 256 bits
        java.security.SecureRandom secureRandom = new java.security.SecureRandom();
        secureRandom.nextBytes(randomBytes);
        
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Creates a signature header value for a webhook.
     * Format: t=timestamp,v1=signature
     *
     * @param signature The signature
     * @param timestamp The timestamp when the signature was created
     * @return The formatted signature header value
     */
    public String createSignatureHeader(String signature, long timestamp) {
        return "t=" + timestamp + ",v1=" + signature;
    }

    /**
     * Parses a signature header value.
     *
     * @param header The signature header value
     * @return An array containing [timestamp, signature]
     * @throws IllegalArgumentException if the header format is invalid
     */
    public String[] parseSignatureHeader(String header) {
        if (header == null || header.isEmpty()) {
            throw new IllegalArgumentException("Signature header is empty");
        }
        
        String[] parts = header.split(",");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid signature header format");
        }
        
        String timestampPart = parts[0];
        String signaturePart = parts[1];
        
        if (!timestampPart.startsWith("t=")) {
            throw new IllegalArgumentException("Invalid timestamp format in signature header");
        }
        
        if (!signaturePart.startsWith("v1=")) {
            throw new IllegalArgumentException("Invalid signature format in signature header");
        }
        
        String timestamp = timestampPart.substring(2);
        String signature = signaturePart.substring(3);
        
        return new String[]{timestamp, signature};
    }

    /**
     * Generates a signature for a webhook notification.
     *
     * @param webhookId The webhook ID
     * @param eventId The event ID
     * @param payload The payload
     * @param timestamp The timestamp
     * @param secretKey The secret key
     * @return The signature
     */
    public String generateWebhookSignature(
            UUID webhookId, UUID eventId, String payload, long timestamp, String secretKey) {
        
        String signaturePayload = webhookId.toString() + "." + 
                                  eventId.toString() + "." + 
                                  timestamp + "." + 
                                  payload;
        
        return generateHmacSignature(signaturePayload, secretKey);
    }

    /**
     * Verifies a webhook signature.
     *
     * @param webhookId The webhook ID
     * @param eventId The event ID
     * @param payload The payload
     * @param signatureHeader The signature header
     * @param secretKey The secret key
     * @param maxAgeSeconds Maximum allowed age of the signature in seconds
     * @return true if the signature is valid, false otherwise
     */
    public boolean verifyWebhookSignature(
            UUID webhookId, UUID eventId, String payload, 
            String signatureHeader, String secretKey, int maxAgeSeconds) {
        
        try {
            // Parse the signature header
            String[] parts = parseSignatureHeader(signatureHeader);
            long timestamp = Long.parseLong(parts[0]);
            String signature = parts[1];
            
            // Check if the signature is too old
            long now = System.currentTimeMillis() / 1000;
            if (now - timestamp > maxAgeSeconds) {
                logger.warn("Webhook signature is too old: {} seconds", now - timestamp);
                return false;
            }
            
            // Recreate the signature payload
            String signaturePayload = webhookId.toString() + "." + 
                                      eventId.toString() + "." + 
                                      timestamp + "." + 
                                      payload;
            
            // Verify the signature
            return verifyHmacSignature(signaturePayload, signature, secretKey);
            
        } catch (Exception e) {
            logger.error("Webhook signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Compares two strings in constant time to prevent timing attacks.
     *
     * @param a The first string
     * @param b The second string
     * @return true if the strings are equal, false otherwise
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        
        if (aBytes.length != bBytes.length) {
            return false;
        }
        
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        
        return result == 0;
    }
}
