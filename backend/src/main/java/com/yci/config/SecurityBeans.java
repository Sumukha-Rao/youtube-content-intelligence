package com.yci.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing lives in its own configuration class on purpose.
 *
 * Declaring it inside {@code WebConfig} creates a cycle at startup:
 * WebConfig needs AuthInterceptor, which needs AuthService, which needs the
 * PasswordEncoder that WebConfig is still constructing.
 */
@Configuration
public class SecurityBeans {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
