package com.exquy.webhook.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Utility class for generating unique identifiers.
 * Provides methods for creating various types of IDs used throughout the system.
 */
@Component
public class IdGenerator {

    private static final Logger logger = LoggerFactory.getLogger(IdGenerator.class);
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a random UUID (v4).
     * This is the primary method used for generating transaction IDs.
     *
     * @return A new random UUID
     */
    public UUID generateUUID() {
        return UUID.randomUUID();
    }

    /**
     * Validates if a string is a valid UUID.
     *
     * @param uuidString The string to validate
     * @return true if the string is a valid UUID, false otherwise
     */
    public boolean isValidUUID(String uuidString) {
        if (uuidString == null || uuidString.isEmpty()) {
            return false;
        }
        
        try {
            UUID.fromString(uuidString);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Converts a string to a UUID.
     *
     * @param uuidString The string to convert
     * @return The UUID, or null if the string is not a valid UUID
     */
    public UUID parseUUID(String uuidString) {
        if (!isValidUUID(uuidString)) {
            return null;
        }
        
        return UUID.fromString(uuidString);
    }

    /**
     * Generates a time-based identifier.
     * Format: yyyyMMddHHmmss-randomHex
     * This is useful for generating human-readable, chronologically sortable IDs.
     *
     * @return A time-based identifier string
     */
    public String generateTimeBasedId() {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = String.format("%04d%02d%02d%02d%02d%02d",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                now.getHour(), now.getMinute(), now.getSecond());
        
        // Add some randomness to ensure uniqueness
        String randomPart = generateRandomHex(6);
        
        return timestamp + "-" + randomPart;
    }

    /**
     * Generates a short ID based on UUID but more compact.
     * This is useful for references and where shorter IDs are preferred.
     *
     * @return A short unique identifier
     */
    public String generateShortId() {
        UUID uuid = UUID.randomUUID();
        long mostSignificantBits = uuid.getMostSignificantBits();
        long leastSignificantBits = uuid.getLeastSignificantBits();
        
        return Long.toHexString(mostSignificantBits) + Long.toHexString(leastSignificantBits);
    }

    /**
     * Generates a reference code for a transaction.
     * Format: TX-prefix-shortHex
     * Useful for customer-facing reference codes.
     *
     * @param prefix A prefix to include in the reference
     * @return A reference code
     */
    public String generateReferenceCode(String prefix) {
        String shortHex = generateRandomHex(8);
        return "TX-" + prefix + "-" + shortHex;
    }

    /**
     * Generates a random hexadecimal string of the specified length.
     *
     * @param length The desired length of the hexadecimal string
     * @return A random hexadecimal string
     */
    public String generateRandomHex(int length) {
        byte[] bytes = new byte[(length + 1) / 2]; // Each byte generates 2 hex chars
        secureRandom.nextBytes(bytes);
        
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        
        return hex.substring(0, length);
    }

    /**
     * Generates a time-based UUID (v1-like).
     * This is not a standard v1 UUID but uses a similar approach.
     *
     * @return A time-based UUID
     */
    public UUID generateTimeBasedUUID() {
        long timeMillis = LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli();
        
        // Mix in some randomness
        byte[] randomBytes = new byte[8];
        secureRandom.nextBytes(randomBytes);
        
        long random = 0;
        for (int i = 0; i < 8; i++) {
            random = (random << 8) | (randomBytes[i] & 0xff);
        }
        
        // Construct a UUID using time as most significant bits and random as least significant bits
        return new UUID(timeMillis, random);
    }

    /**
     * Extracts the timestamp from a time-based ID.
     *
     * @param timeBasedId The time-based ID to parse
     * @return The extracted LocalDateTime, or null if parsing fails
     */
    public LocalDateTime extractTimestampFromTimeBasedId(String timeBasedId) {
        try {
            String timestampPart = timeBasedId.split("-")[0];
            
            int year = Integer.parseInt(timestampPart.substring(0, 4));
            int month = Integer.parseInt(timestampPart.substring(4, 6));
            int day = Integer.parseInt(timestampPart.substring(6, 8));
            int hour = Integer.parseInt(timestampPart.substring(8, 10));
            int minute = Integer.parseInt(timestampPart.substring(10, 12));
            int second = Integer.parseInt(timestampPart.substring(12, 14));
            
            return LocalDateTime.of(year, month, day, hour, minute, second);
        } catch (Exception e) {
            logger.warn("Failed to extract timestamp from time-based ID: {}", timeBasedId);
            return null;
        }
    }
}
