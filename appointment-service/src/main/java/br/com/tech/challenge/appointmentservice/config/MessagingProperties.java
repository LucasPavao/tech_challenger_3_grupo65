package br.com.tech.challenge.appointmentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
        String historyExchange,
        String historyRoutingKey,
        String notificationExchange,
        String notificationRoutingKey,
        boolean publishNotification
) {
}
