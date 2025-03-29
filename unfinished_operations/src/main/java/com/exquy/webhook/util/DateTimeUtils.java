package com.exquy.webhook.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Utility class for date and time operations.
 * Provides methods for conversion, formatting, and calculation of 
 * dates and times used throughout the system.
 */
@Component
public class DateTimeUtils {

    private static final Logger logger = LoggerFactory.getLogger(DateTimeUtils.class);

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter READABLE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();

    /**
     * Gets the current timestamp as a LocalDateTime.
     *
     * @return The current LocalDateTime
     */
    public LocalDateTime getCurrentDateTime() {
        return LocalDateTime.now();
    }

    /**
     * Gets the current timestamp as milliseconds since epoch.
     *
     * @return The current time in milliseconds
     */
    public long getCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Gets the current timestamp as seconds since epoch.
     *
     * @return The current time in seconds
     */
    public long getCurrentTimeSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    /**
     * Formats a LocalDateTime using the ISO formatter.
     *
     * @param dateTime The LocalDateTime to format
     * @return The formatted string
     */
    public String formatIso(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return ISO_FORMATTER.format(dateTime);
    }

    /**
     * Formats a LocalDateTime as a compact timestamp (yyyyMMddHHmmss).
     *
     * @param dateTime The LocalDateTime to format
     * @return The formatted string
     */
    public String formatTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return TIMESTAMP_FORMATTER.format(dateTime);
    }

    /**
     * Formats a LocalDateTime in a human-readable format.
     *
     * @param dateTime The LocalDateTime to format
     * @return The formatted string
     */
    public String formatReadable(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return READABLE_FORMATTER.format(dateTime);
    }

    /**
     * Parses an ISO formatted string to a LocalDateTime.
     *
     * @param dateTimeStr The string to parse
     * @return The parsed LocalDateTime, or null if parsing fails
     */
    public LocalDateTime parseIso(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }
        
        try {
            return LocalDateTime.parse(dateTimeStr, ISO_FORMATTER);
        } catch (DateTimeParseException e) {
            logger.warn("Failed to parse ISO date-time: {}", dateTimeStr);
            return null;
        }
    }

    /**
     * Parses a timestamp string (yyyyMMddHHmmss) to a LocalDateTime.
     *
     * @param timestampStr The string to parse
     * @return The parsed LocalDateTime, or null if parsing fails
     */
    public LocalDateTime parseTimestamp(String timestampStr) {
        if (timestampStr == null || timestampStr.isEmpty()) {
            return null;
        }
        
        try {
            return LocalDateTime.parse(timestampStr, TIMESTAMP_FORMATTER);
        } catch (DateTimeParseException e) {
            logger.warn("Failed to parse timestamp: {}", timestampStr);
            return null;
        }
    }

    /**
     * Calculates the age of a LocalDateTime in seconds.
     *
     * @param dateTime The LocalDateTime to calculate age for
     * @return The age in seconds
     */
    public long getAgeInSeconds(LocalDateTime dateTime) {
        if (dateTime == null) {
            return 0;
        }
        
        return ChronoUnit.SECONDS.between(dateTime, LocalDateTime.now());
    }

    /**
     * Calculates the age of a LocalDateTime in minutes.
     *
     * @param dateTime The LocalDateTime to calculate age for
     * @return The age in minutes
     */
    public long getAgeInMinutes(LocalDateTime dateTime) {
        if (dateTime == null) {
            return 0;
        }
        
        return ChronoUnit.MINUTES.between(dateTime, LocalDateTime.now());
    }

    /**
     * Calculates the time difference between two LocalDateTimes.
     *
     * @param start The start time
     * @param end The end time
     * @return The duration between the times
     */
    public Duration getDuration(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return Duration.ZERO;
        }
        
        return Duration.between(start, end);
    }

    /**
     * Formats a duration in a human-readable format.
     *
     * @param duration The duration to format
     * @return The formatted duration string
     */
    public String formatDuration(Duration duration) {
        if (duration == null) {
            return "0s";
        }
        
        long days = duration.toDaysPart();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        
        StringBuilder sb = new StringBuilder();
        
        if (days > 0) {
            sb.append(days).append("d ");
        }
        
        if (hours > 0 || days > 0) {
            sb.append(hours).append("h ");
        }
        
        if (minutes > 0 || hours > 0 || days > 0) {
            sb.append(minutes).append("m ");
        }
        
        sb.append(seconds).append("s");
        
        return sb.toString();
    }

    /**
     * Converts a Date to LocalDateTime.
     *
     * @param date The Date to convert
     * @return The equivalent LocalDateTime
     */
    public LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        
        return LocalDateTime.ofInstant(date.toInstant(), DEFAULT_ZONE);
    }

    /**
     * Converts a LocalDateTime to Date.
     *
     * @param dateTime The LocalDateTime to convert
     * @return The equivalent Date
     */
    public Date toDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        
        return Date.from(dateTime.atZone(DEFAULT_ZONE).toInstant());
    }

    /**
     * Converts epoch milliseconds to LocalDateTime.
     *
     * @param epochMilli The epoch time in milliseconds
     * @return The equivalent LocalDateTime
     */
    public LocalDateTime fromEpochMilli(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), DEFAULT_ZONE);
    }

    /**
     * Converts a LocalDateTime to epoch milliseconds.
     *
     * @param dateTime The LocalDateTime to convert
     * @return The equivalent epoch time in milliseconds
     */
    public long toEpochMilli(LocalDateTime dateTime) {
        if (dateTime == null) {
            return 0;
        }
        
        return dateTime.atZone(DEFAULT_ZONE).toInstant().toEpochMilli();
    }

    /**
     * Checks if a LocalDateTime is older than a specified duration.
     *
     * @param dateTime The LocalDateTime to check
     * @param duration The duration threshold
     * @return true if the date is older than the duration, false otherwise
     */
    public boolean isOlderThan(LocalDateTime dateTime, Duration duration) {
        if (dateTime == null) {
            return false;
        }
        
        return LocalDateTime.now().minus(duration).isAfter(dateTime);
    }

    /**
     * Checks if a LocalDateTime is between two other LocalDateTimes.
     *
     * @param dateTime The LocalDateTime to check
     * @param start The start of the range (inclusive)
     * @param end The end of the range (inclusive)
     * @return true if the dateTime is within the range, false otherwise
     */
    public boolean isBetween(LocalDateTime dateTime, LocalDateTime start, LocalDateTime end) {
        if (dateTime == null || start == null || end == null) {
            return false;
        }
        
        return !dateTime.isBefore(start) && !dateTime.isAfter(end);
    }
}
