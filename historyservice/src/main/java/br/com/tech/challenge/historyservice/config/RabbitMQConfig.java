package br.com.tech.challenge.historyservice.config;

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

@Configuration
public class RabbitMQConfig {

    @Value("${app.rabbitmq.exchange}")
    private String exchangeName;

    @Value("${app.rabbitmq.queue}")
    private String queueName;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    @Bean
    public TopicExchange historyExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Queue historyQueue() {
        return QueueBuilder.durable(queueName)
                .deadLetterExchange(exchangeName + ".dlx")
                .deadLetterRoutingKey(routingKey)
                .build();
    }

    @Bean
    public Binding historyBinding() {
        return BindingBuilder.bind(historyQueue()).to(historyExchange()).with(routingKey);
    }

    @Bean
    public TopicExchange historyDeadLetterExchange() {
        return new TopicExchange(exchangeName + ".dlx", true, false);
    }

    @Bean
    public Queue historyDeadLetterQueue() {
        return QueueBuilder.durable(queueName + ".dlq").build();
    }

    @Bean
    public Binding historyDeadLetterBinding() {
        return BindingBuilder.bind(historyDeadLetterQueue())
                .to(historyDeadLetterExchange())
                .with(routingKey);
    }

    /**
     * Usa o JsonMapper auto-configurado pelo Spring Boot em vez de um mapper proprio,
     * para que o listener respeite as propriedades spring.jackson.* -- em especial
     * deserialization.fail-on-unknown-properties, que manda payload fora do contrato para a DLQ.
     */
    @Bean
    public MessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }
}
