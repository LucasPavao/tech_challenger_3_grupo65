package br.com.tech.challenge.appointmentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rabbitmq")
public record MessagingProperties(
        String appointmentExchange,
        String historyRoutingKey,
        String notificationRoutingKey
) {
}
