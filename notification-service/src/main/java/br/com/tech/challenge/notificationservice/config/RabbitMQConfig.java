package br.com.tech.challenge.notificationservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Topologia RabbitMQ para o notification-service.
 *
 * O notification-service consome eventos do appointment-service através de um TopicExchange.
 * Cada evento contém informações sobre mudanças nas consultas, que são processadas para
 * envio de notificações/lembretes aos pacientes.
 *
 * Topologia:
 * - Exchange: appointment.exchange (TopicExchange)
 * - Queue: notification.queue
 * - Routing Key: notification.created (binding para cada evento de notificação)
 * - DLQ: notification.queue.dlq (Dead Letter Queue para reprocessamento)
 */
@Configuration
public class RabbitMQConfig {

    @Value("${app.rabbitmq.exchange}")
    private String exchangeName;

    @Value("${app.rabbitmq.queue}")
    private String queueName;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    @Bean
    public TopicExchange appointmentExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(queueName)
                .deadLetterExchange(exchangeName + ".dlx")
                .deadLetterRoutingKey(routingKey + ".dlq")
                .build();
    }

    @Bean
    public Binding notificationBinding(Queue notificationQueue, TopicExchange appointmentExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(appointmentExchange)
                .with(routingKey);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(exchangeName + ".dlx", true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(queueName + ".dlq").build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with(routingKey + ".dlq");
    }

    /**
     * Usa o JsonMapper auto-configurado pelo Spring Boot em vez de um mapper próprio,
     * para que o listener respeite as propriedades spring.jackson.* — em especial
     * deserialization.fail-on-unknown-properties, que manda payload fora do contrato para a DLQ.
     */
    @Bean
    public MessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }
}
