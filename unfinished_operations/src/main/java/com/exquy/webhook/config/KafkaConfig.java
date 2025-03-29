package com.exquy.webhook.config;

import com.company.transactionrecovery.infrastructure.kafka.dto.TransactionEventMessage;
import com.company.transactionrecovery.infrastructure.kafka.dto.WebhookEventMessage;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class for Apache Kafka integration.
 * Sets up Kafka producers, consumers, and topics for the transaction recovery system.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String consumerGroupId;

    @Value("${spring.kafka.topics.transaction-events}")
    private String transactionEventsTopic;

    @Value("${spring.kafka.topics.webhook-events}")
    private String webhookEventsTopic;

    @Value("${spring.kafka.topics.transaction-events.partitions:3}")
    private int transactionEventsPartitions;

    @Value("${spring.kafka.topics.webhook-events.partitions:3}")
    private int webhookEventsPartitions;

    @Value("${spring.kafka.topics.replication-factor:1}")
    private short replicationFactor;

    /**
     * Kafka admin client configurations for topic management.
     */
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return new KafkaAdmin(configs);
    }

    /**
     * Creates the transaction events topic if it doesn't exist.
     */
    @Bean
    public NewTopic transactionEventsTopic() {
        return TopicBuilder.name(transactionEventsTopic)
                .partitions(transactionEventsPartitions)
                .replicas(replicationFactor)
                .build();
    }

    /**
     * Creates the webhook events topic if it doesn't exist.
     */
    @Bean
    public NewTopic webhookEventsTopic() {
        return TopicBuilder.name(webhookEventsTopic)
                .partitions(webhookEventsPartitions)
                .replicas(replicationFactor)
                .build();
    }

    /**
     * Producer configuration for sending messages to Kafka.
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Enable idempotent producer to avoid duplicates
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Ensure messages are properly acknowledged
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        // Retry logic
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * Kafka template for transaction events producer.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * Consumer configuration for transaction events.
     */
    @Bean
    public ConsumerFactory<String, TransactionEventMessage> transactionEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId + "-transaction");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionEventMessage.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.company.transactionrecovery.infrastructure.kafka.dto");
        // Ensure consumer commits offsets only after processing
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Kafka listener container factory for transaction events.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionEventMessage> transactionKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, TransactionEventMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(transactionEventConsumerFactory());
        // Configure concurrency
        factory.setConcurrency(3);
        // Configure batch processing if needed
        factory.setBatchListener(false);
        // Enable transaction support for consumers
        factory.getContainerProperties().setTransactionManager(null); // Set your transaction manager if needed
        return factory;
    }

    /**
     * Consumer configuration for webhook events.
     */
    @Bean
    public ConsumerFactory<String, WebhookEventMessage> webhookEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId + "-webhook");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, WebhookEventMessage.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.company.transactionrecovery.infrastructure.kafka.dto");
        // Ensure consumer commits offsets only after processing
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Kafka listener container factory for webhook events.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, WebhookEventMessage> webhookKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, WebhookEventMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(webhookEventConsumerFactory());
        // Configure concurrency
        factory.setConcurrency(3);
        // Configure batch processing if needed
        factory.setBatchListener(false);
        return factory;
    }
}
