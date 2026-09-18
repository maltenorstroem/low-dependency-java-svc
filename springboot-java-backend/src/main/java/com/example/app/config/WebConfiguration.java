package com.example.app.config;

import com.example.app.web.AccessLogFilter;
import com.example.app.web.BodyLimitFilter;
import com.example.app.web.LoadSheddingFilter;
import com.example.app.web.QueryParamInterceptor;
import com.example.app.web.RequestIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/**
 * MVC wiring that is not auto-configured.
 *
 * <p>The filters are registered explicitly rather than as {@code @Component}s so their order
 * relative to the security chain is stated rather than inferred. Load shedding and the body cap
 * both have to run before a token is verified: refusing early is the point of the first, and the
 * second must reject an oversized body before anything tries to read it.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new QueryParamInterceptor());
    }

    @Bean
    FilterRegistrationBean<LoadSheddingFilter> loadSheddingFilter(
            AppProperties properties, MeterRegistry registry, ObjectMapper mapper) {
        return registered(
                new LoadSheddingFilter(properties.maxConcurrentRequests(), registry, mapper),
                Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        // Just after shedding, so a shed request still carries an id.
        return registered(new RequestIdFilter(), Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Bean
    FilterRegistrationBean<BodyLimitFilter> bodyLimitFilter(AppProperties properties, ObjectMapper mapper) {
        return registered(new BodyLimitFilter(properties.maxBodyBytes(), mapper),
                Ordered.HIGHEST_PRECEDENCE + 20);
    }

    @Bean
    FilterRegistrationBean<AccessLogFilter> accessLogFilter() {
        // Inside the security chain, so the log line can name the authenticated subject.
        return registered(new AccessLogFilter(), Ordered.LOWEST_PRECEDENCE - 10);
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> registered(T filter, int order) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        return registration;
    }
}
