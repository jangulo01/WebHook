package com.exquy.webhook.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.sql.DataSource;
import java.util.Arrays;

/**
 * Configuration class for web security settings.
 * Configures authentication, authorization, CORS, CSRF, and other security features.
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JwtTokenFilter jwtTokenFilter;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    @Value("${security.jwt.expiration:86400}")
    private int jwtExpiration;

    @Value("${security.allowed-origins}")
    private String[] allowedOrigins;

    /**
     * Configures the HTTP security settings.
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for REST API
            .csrf().disable()
            // Enable CORS
            .cors().and()
            // Set session management to stateless
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
            // Set permissions on endpoints
            .authorizeRequests()
                // Public endpoints
                .antMatchers("/api/health/**").permitAll()
                .antMatchers("/api/webhooks/acknowledge").permitAll()
                // Transaction endpoints require transaction role
                .antMatchers("/api/transactions/**").hasAnyRole("TRANSACTION", "ADMIN")
                // Webhook configuration endpoints require webhook role
                .antMatchers("/api/webhooks/**").hasAnyRole("WEBHOOK", "ADMIN")
                // Admin endpoints require admin role
                .antMatchers("/api/admin/**").hasRole("ADMIN")
                // All other requests need authentication
                .anyRequest().authenticated()
                .and()
            // Add JWT token filter
            .addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class);

        // If you want to allow iframe embedding from specific domains
        http.headers().frameOptions().sameOrigin();
    }

    /**
     * Configures the authentication manager.
     */
    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.jdbcAuthentication()
            .dataSource(dataSource)
            .usersByUsernameQuery("SELECT username, password, enabled FROM users WHERE username = ?")
            .authoritiesByUsernameQuery("SELECT username, role FROM user_roles WHERE username = ?")
            .passwordEncoder(passwordEncoder());
    }

    /**
     * Bean for password encoder used in authentication.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Configures CORS settings.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setExposedHeaders(Arrays.asList("X-Total-Count", "X-Transaction-ID"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * JWT token filter class.
     * In a real implementation, this would be in a separate file.
     */
    @Configuration
    public static class JwtTokenFilter extends UsernamePasswordAuthenticationFilter {
        // This is a placeholder. In a real implementation, this would contain
        // the logic to validate JWT tokens and set up the SecurityContext.
        // For brevity, the implementation is omitted here.
    }

    /**
     * Bean for JWT token provider.
     */
    @Bean
    public JwtTokenProvider jwtTokenProvider() {
        return new JwtTokenProvider(jwtSecret, jwtExpiration);
    }

    /**
     * JWT token provider class.
     * In a real implementation, this would be in a separate file.
     */
    public static class JwtTokenProvider {
        private final String secret;
        private final int expiration;

        public JwtTokenProvider(String secret, int expiration) {
            this.secret = secret;
            this.expiration = expiration;
        }

        // Methods for generating and validating tokens would be implemented here
        public String generateToken(String username) {
            // Implementation would generate a JWT token
            return "token";
        }

        public boolean validateToken(String token) {
            // Implementation would validate a JWT token
            return true;
        }
    }
}
