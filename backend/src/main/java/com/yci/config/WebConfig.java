package com.yci.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yci.security.AuthInterceptor;
import com.yci.security.CurrentUserArgumentResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:*}")
    private String allowedOrigins;

    private final AuthInterceptor authInterceptor;
    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    public WebConfig(AuthInterceptor authInterceptor, CurrentUserArgumentResolver resolver) {
        this.authInterceptor = authInterceptor;
        this.currentUserArgumentResolver = resolver;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    /** Everything under /api requires a bearer token except auth and health. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/signup", "/api/auth/login", "/api/health");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    /** Shared WebClient builder: large in-memory buffer + a lenient Jackson mapper
     *  (ignores unknown JSON fields) for ML/LLM payloads. */
    @Bean
    public WebClient.Builder webClientBuilder() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return WebClient.builder().codecs(c -> {
            c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
            c.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(mapper));
            c.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper));
        });
    }
}
