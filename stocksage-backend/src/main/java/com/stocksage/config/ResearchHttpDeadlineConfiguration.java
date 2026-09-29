package com.stocksage.config;

import com.stocksage.tool.ToolCallContext;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;

/** Adds research deadlines without replacing model beans or explicitly supplied test transports. */
@Configuration
public class ResearchHttpDeadlineConfiguration {
    @Bean
    static BeanPostProcessor researchHttpDeadlineFactories(ConfigurableListableBeanFactory beans) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof ClientHttpRequestFactoryBuilder<?> builder)
                        || !name.equals("clientHttpRequestFactoryBuilder")) return bean;
                var definition = beans.getBeanDefinition(name);
                if (!HttpClientAutoConfiguration.class.getName().equals(definition.getFactoryBeanName())) return bean;
                return (ClientHttpRequestFactoryBuilder<ClientHttpRequestFactory>) settings ->
                        withDeadline(builder.build(settings), settings);
            }
        };
    }

    static ClientHttpRequestFactory withDeadline(ClientHttpRequestFactory delegate,
                                                 ClientHttpRequestFactorySettings configured) {
        var settings = configured == null ? ClientHttpRequestFactorySettings.defaults() : configured;
        var bounded = ClientHttpRequestFactoryBuilder.jdk().build(settings);
        return (uri, method) -> {
            var deadline = ToolCallContext.currentRunDeadline();
            if (deadline == null) return delegate.createRequest(uri, method);
            deadline.remainingMillis();
            // Serialize before sampling the remaining HTTP time; no new worker or client per call.
            return new AbstractClientHttpRequest() {
                private final ByteArrayOutputStream body = new ByteArrayOutputStream();
                @Override public HttpMethod getMethod() { return method; }
                @Override public URI getURI() { return uri; }
                @Override protected OutputStream getBodyInternal(HttpHeaders headers) { return body; }

                @Override
                protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
                    ClientHttpRequest request;
                    synchronized (bounded) {
                        Duration timeout = Duration.ofMillis(deadline.remainingMillis());
                        Duration local = settings.readTimeout();
                        if (local != null && !local.isNegative() && !local.isZero()
                                && local.compareTo(timeout) < 0) timeout = local;
                        // Spring 6.2 snapshots this value into the request; network I/O is outside the lock.
                        bounded.setReadTimeout(timeout);
                        request = bounded.createRequest(uri, method);
                    }
                    request.getHeaders().putAll(headers);
                    request.getAttributes().putAll(getAttributes());
                    body.writeTo(request.getBody());
                    deadline.remainingMillis();
                    return request.execute();
                }
            };
        };
    }
}
