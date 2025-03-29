package com.company.transactionrecovery.domain.service.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Service responsible for handling security aspects of webhooks.
 * Provides methods for token generation, validation, and signature verification.
 */
@Service
public class WebhookSecurityService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookSecurityService.class);

    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom;

    @Value("${webhook.signature.algorithm:HmacSHA256}")
    private String signatureAlgorithm;

    @Value("${webhook.security.token-length:32}")
    private int tokenLength;

    /**
     * Constructor.
     */
    public WebhookSecurityService() {
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.secureRandom = new SecureRandom();
    }

    /**
     * Generates a new security token for webhook signatures.
     *
     * @return The generated token
     */
    public String generateSecurityToken() {
        byte[] tokenBytes = new byte[tokenLength];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    /**
     * Hashes a security token for storage.
     * We don't store the actual token in the database, only a hash of it.
     *
     * @param token The token to hash
     * @return The hashed token
     */
    public String hashSecurityToken(String token) {
        return passwordEncoder.encode(token);
    }

    /**
     * Verifies if a provided token matches the stored hash.
     *
     * @param providedToken The token to verify
     * @param storedHash The stored hash
     * @return true if the token matches the hash, false otherwise
     */
    public boolean verifySecurityToken(String providedToken, String storedHash) {
        return passwordEncoder.matches(providedToken, storedHash);
    }

    /**
     * Generates a signature for a webhook payload.
     *
     * @param payload The payload to sign
     * @param secretKey The secret key to use
     * @return The generated signature
     * @throws NoSuchAlgorithmException if the signature algorithm is not available
     * @throws InvalidKeyException if the key is invalid
     */
    public String generateSignature(String payload, String secretKey) 
            throws NoSuchAlgorithmException, InvalidKeyException {
        
        Mac hmac = Mac.getInstance(signatureAlgorithm);
        SecretKeySpec keySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8), signatureAlgorithm);
        
        hmac.init(keySpec);
        byte[] hmacBytes = hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        
        return Base64.getEncoder().encodeToString(hmacBytes);
    }

    /**
     * Verifies a webhook signature.
     *
     * @param payload The original payload
     * @param signature The provided signature
     * @param secretKey The secret key
     * @return true if the signature is valid, false otherwise
     */
    public boolean verifySignature(String payload, String signature, String secretKey) {
        try {
            String expectedSignature = generateSignature(payload, secretKey);
            return constantTimeEquals(expectedSignature, signature);
        } catch (Exception e) {
            logger.error("Error verifying webhook signature: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Generates a unique nonce for webhook requests.
     * This helps prevent replay attacks.
     *
     * @return A unique nonce
     */
    public String generateNonce() {
        return UUID.randomUUID().toString();
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

    /**
     * Validates that a webhook callback URL meets security requirements.
     *
     * @param url The URL to validate
     * @return true if the URL is secure, false otherwise
     */
    public boolean isSecureWebhookUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        // Must use HTTPS
        if (!url.toLowerCase().startsWith("https://")) {
            return false;
        }
        
        // Disallow localhost and loopback addresses
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.contains("localhost") || 
            lowerUrl.contains("127.0.0.1") || 
            lowerUrl.contains("::1")) {
            return false;
        }
        
        // Additional security checks can be added here
        
        return true;
    }

    /**
     * Creates a replay protection header.
     * This combines a timestamp and nonce which allows recipients to
     * protect against replay attacks.
     *
     * @return Map containing timestamp and nonce
     */
    public String createReplayProtectionHeader() {
        long timestamp = System.currentTimeMillis();
        String nonce = generateNonce();
        
        return "t=" + timestamp + ",n=" + nonce;
    }
}
