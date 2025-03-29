package com.company.transactionrecovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Main application class for the Transaction Recovery Service.
 * This service handles recovery of incomplete transactions in an API REST system
 * that reports payments between branches.
 *
 * The application implements two complementary solutions:
 * 1. Inconclusive Operations Protocol: Implements idempotence and status query mechanisms
 * 2. Webhook Notification System: Implements proactive notifications to originators
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableTransactionManagement
public class TransactionRecoveryApplication {

    /**
     * Main entry point for the application.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(TransactionRecoveryApplication.class, args);
    }
}
