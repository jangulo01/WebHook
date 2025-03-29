package com.exquy.webhook.infrastructure.http;

import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for HTTP client used in webhook communications.
 * Provides optimized RestTemplate and HttpClient instances with
 * connection pooling, timeouts, and keep-alive settings.
 */
@Configuration
public class HttpClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientConfig.class);

    @Value("${webhook.connection.timeout-ms:5000}")
    private int connectionTimeout;

    @Value("${webhook.socket.timeout-ms:10000}")
    private int socketTimeout;

    @Value("${webhook.connection-request.timeout-ms:2000}")
    private int connectionRequestTimeout;

    @Value("${webhook.max-total-connections:100}")
    private int maxTotalConnections;

    @Value("${webhook.max-connections-per-route:20}")
    private int maxConnectionsPerRoute;

    @Value("${webhook.connection.keep-alive-ms:30000}")
    private int defaultKeepAliveTime;

    @Value("${webhook.connection.validate-after-inactivity-ms:10000}")
    private int validateAfterInactivity;

    private PoolingHttpClientConnectionManager connectionManager;

    /**
     * Creates the RestTemplate bean with optimized HTTP client.
     *
     * @return Configured RestTemplate instance
     */
    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate(clientHttpRequestFactory());
        return restTemplate;
    }

    /**
     * Creates a request factory with the pooled HTTP client.
     *
     * @return ClientHttpRequestFactory instance
     */
    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient());
        return factory;
    }

    /**
     * Creates and configures the pooled HTTP client.
     *
     * @return CloseableHttpClient instance
     */
    @Bean
    public CloseableHttpClient httpClient() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(connectionRequestTimeout)
                .setConnectTimeout(connectionTimeout)
                .setSocketTimeout(socketTimeout)
                .build();

        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManager(poolingConnectionManager())
                .setKeepAliveStrategy(connectionKeepAliveStrategy())
                .build();
    }

    /**
     * Creates and configures the connection manager with connection pooling.
     *
     * @return PoolingHttpClientConnectionManager instance
     */
    @Bean
    public PoolingHttpClientConnectionManager poolingConnectionManager() {
        SSLConnectionSocketFactory sslSocketFactory = null;
        try {
            // Setup SSL context for HTTPS connections
            SSLContext sslContext = new SSLContextBuilder()
                    .loadTrustMaterial(null, new TrustSelfSignedStrategy())
                    .build();
            
            sslSocketFactory = new SSLConnectionSocketFactory(
                    sslContext, 
                    new String[]{"TLSv1.2", "TLSv1.3"}, // Supported protocols
                    null, // Default cipher suites
                    NoopHostnameVerifier.INSTANCE); // Not verifying hostnames in development
            
        } catch (NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
            logger.error("Failed to create SSL socket factory", e);
            // Fall back to defaults if SSL setup fails
        }

        Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder
                .<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", sslSocketFactory != null 
                        ? sslSocketFactory 
                        : SSLConnectionSocketFactory.getSocketFactory())
                .build();

        PoolingHttpClientConnectionManager poolingConnectionManager = 
                new PoolingHttpClientConnectionManager(socketFactoryRegistry);
        
        // Set total amount of connections
        poolingConnectionManager.setMaxTotal(maxTotalConnections);
        
        // Set maximum connections per route
        poolingConnectionManager.setDefaultMaxPerRoute(maxConnectionsPerRoute);
        
        // Set period of inactivity in milliseconds after which connections will be re-validated
        poolingConnectionManager.setValidateAfterInactivity(validateAfterInactivity);
        
        this.connectionManager = poolingConnectionManager;
        
        return poolingConnectionManager;
    }

    /**
     * Creates a keep-alive strategy for HTTP connections.
     *
     * @return ConnectionKeepAliveStrategy instance
     */
    @Bean
    public ConnectionKeepAliveStrategy connectionKeepAliveStrategy() {
        return new ConnectionKeepAliveStrategy() {
            @Override
            public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
                HeaderElementIterator it = new BasicHeaderElementIterator(
                        response.headerIterator(HTTP.CONN_KEEP_ALIVE));
                
                while (it.hasNext()) {
                    HeaderElement he = it.nextElement();
                    String param = he.getName();
                    String value = he.getValue();
                    
                    if (value != null && param.equalsIgnoreCase("timeout")) {
                        try {
                            return Long.parseLong(value) * 1000;
                        } catch (NumberFormatException ignore) {
                            // Ignore invalid timeout values
                        }
                    }
                }
                
                // Default keep-alive time if not specified by server
                return defaultKeepAliveTime;
            }
        };
    }

    /**
     * Scheduled task to close expired and idle connections.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedRate = 30000)
    public void closeExpiredConnections() {
        if (connectionManager != null) {
            // Close expired connections
            connectionManager.closeExpiredConnections();
            
            // Close connections that have been idle for too long
            connectionManager.closeIdleConnections(60, TimeUnit.SECONDS);
            
            logger.debug("Connection pool stats - Available: {}, Leased: {}, Pending: {}, Max: {}",
                    connectionManager.getTotalStats().getAvailable(),
                    connectionManager.getTotalStats().getLeased(),
                    connectionManager.getTotalStats().getPending(),
                    connectionManager.getTotalStats().getMax());
        }
    }
}
