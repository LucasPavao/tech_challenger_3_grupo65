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

/**
 * Topologia RabbitMQ EXCLUSIVA do notification-service.
 *
 * IMPORTANTE: o exchange abaixo (app.rabbitmq.exchange) precisa ser o MESMO
 * nome usado pelo appointment-service ao publicar o evento, e a routing-key
 * também precisa bater com o binding usado lá. Alinhar esses nomes com quem
 * está cuidando da mensageria (Pessoa 4) antes de integrar de verdade.
 *
 * A fila aqui é "notification.queue" - propositalmente diferente da
 * "history.queue" do history-service, para que os dois serviços NÃO
 * concorram pela mesma fila (competing consumers). Cada serviço deve ter
 * sua própria fila, ainda que ligada ao mesmo exchange.
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
    public TopicExchange notificationExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(queueName)
                .deadLetterExchange(exchangeName + ".dlx")
                .deadLetterRoutingKey(routingKey)
                .build();
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue()).to(notificationExchange()).with(routingKey);
    }

    @Bean
    public TopicExchange notificationDeadLetterExchange() {
        return new TopicExchange(exchangeName + ".dlx", true, false);
    }

    @Bean
    public Queue notificationDeadLetterQueue() {
        return QueueBuilder.durable(queueName + ".dlq").build();
    }

    @Bean
    public Binding notificationDeadLetterBinding() {
        return BindingBuilder.bind(notificationDeadLetterQueue())
                .to(notificationDeadLetterExchange())
                .with(routingKey);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
