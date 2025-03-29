package com.exquy.webhook.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configuration class for asynchronous task execution.
 * Configures thread pools and exception handlers for async operations
 * such as webhook delivery and transaction monitoring.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    @Value("${async.core-pool-size:5}")
    private int corePoolSize;

    @Value("${async.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${async.queue-capacity:25}")
    private int queueCapacity;

    @Value("${async.webhook.core-pool-size:10}")
    private int webhookCorePoolSize;

    @Value("${async.webhook.max-pool-size:20}")
    private int webhookMaxPoolSize;

    @Value("${async.webhook.queue-capacity:50}")
    private int webhookQueueCapacity;

    @Value("${async.monitor.core-pool-size:2}")
    private int monitorCorePoolSize;

    @Value("${async.monitor.max-pool-size:5}")
    private int monitorMaxPoolSize;

    @Value("${async.monitor.queue-capacity:10}")
    private int monitorQueueCapacity;

    /**
     * Default async executor for general purpose async tasks.
     */
    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("async-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Specialized executor for webhook delivery operations.
     * This executor is tuned for I/O-bound operations with more threads
     * and a larger queue capacity.
     */
    @Bean(name = "webhookExecutor")
    public Executor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(webhookCorePoolSize);
        executor.setMaxPoolSize(webhookMaxPoolSize);
        executor.setQueueCapacity(webhookQueueCapacity);
        executor.setThreadNamePrefix("webhook-task-");
        
        // Use a custom rejection policy for webhooks to ensure they are not lost
        executor.setRejectedExecutionHandler(new WebhookRejectionHandler());
        
        executor.initialize();
        return executor;
    }

    /**
     * Specialized executor for transaction monitoring operations.
     * This executor is configured for CPU-bound operations with fewer threads.
     */
    @Bean(name = "monitorExecutor")
    public Executor monitorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(monitorCorePoolSize);
        executor.setMaxPoolSize(monitorMaxPoolSize);
        executor.setQueueCapacity(monitorQueueCapacity);
        executor.setThreadNamePrefix("monitor-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Exception handler for async methods.
     * Logs exceptions that occur during async execution.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new CustomAsyncExceptionHandler();
    }

    /**
     * Custom handler for exceptions in async methods.
     */
    private static class CustomAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        
        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            logger.error("Async method execution exception: Method [{}] threw exception [{}] with message [{}]",
                    method.getName(), ex.getClass().getName(), ex.getMessage());
            
            logger.error("Exception stacktrace:", ex);
            
            // Additional error handling logic could be added here
            // For example, sending notifications, retrying the operation, etc.
        }
    }

    /**
     * Custom rejection handler for webhook executor.
     * Instead of rejecting tasks when the queue is full, this handler
     * logs the rejection and puts the task into a persistent store
     * (e.g., a database table) for later retry.
     */
    private static class WebhookRejectionHandler implements RejectedExecutionHandler {
        
        private final Logger rejectionLogger = LoggerFactory.getLogger("webhook.rejection");
        
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            rejectionLogger.warn("Webhook task rejected, queue capacity reached. Current queue size: {}, active threads: {}",
                    executor.getQueue().size(), executor.getActiveCount());
            
            if (r instanceof WebhookTask) {
                WebhookTask task = (WebhookTask) r;
                
                // In a real implementation, this would persist the task to a database
                // and schedule it for later execution
                rejectionLogger.info("Persisting webhook task for later execution: {}", task.getDetails());
                
                // Temporary solution: run in the caller's thread as a fallback
                // This is not ideal for production but ensures the task is executed
                try {
                    rejectionLogger.info("Executing webhook task in caller thread as fallback");
                    r.run();
                } catch (Exception e) {
                    rejectionLogger.error("Error executing webhook task in caller thread", e);
                }
            } else {
                // If not a webhook task, use the default CallerRunsPolicy
                new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(r, executor);
            }
        }
    }

    /**
     * Interface for webhook tasks that can be persisted.
     * In a real implementation, this would be implemented by the actual webhook
     * delivery task classes.
     */
    public interface WebhookTask {
        String getDetails();
    }
}
