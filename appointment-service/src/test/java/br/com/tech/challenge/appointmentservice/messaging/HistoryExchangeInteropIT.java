package br.com.tech.challenge.appointmentservice.messaging;

import br.com.tech.challenge.appointmentservice.config.MessagingConfiguration;
import br.com.tech.challenge.appointmentservice.config.RabbitMqConfig;
import br.com.tech.challenge.appointmentservice.entity.Appointment;
import br.com.tech.challenge.appointmentservice.event.AppointmentEventStatus;
import br.com.tech.challenge.appointmentservice.service.AppointmentEventPublisher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que o appointment-service consegue publicar na topologia declarada pelo
 * history-service. Regressao para o conflito Direct x Topic na mesma exchange, que
 * derruba o channel com PRECONDITION_FAILED quando os dois compartilham o broker.
 *
 * Carrega apenas as classes de mensageria, sem o AppointmentServiceApplication, para
 * nao exigir Postgres — o servico ainda nao tem nenhum teste que suba contexto completo.
 */
@SpringBootTest(classes = {
        MessagingConfiguration.class,
        RabbitMqConfig.class,
        AppointmentEventPublisher.class
})
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
@Testcontainers
class HistoryExchangeInteropIT {

    private static final String EXCHANGE = "history.exchange";
    private static final String QUEUE = "history.queue";
    private static final String ROUTING_KEY = "history.created";

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @Autowired
    AppointmentEventPublisher publisher;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("app.messaging.history-exchange", () -> EXCHANGE);
        registry.add("app.messaging.history-routing-key", () -> ROUTING_KEY);
        registry.add("app.messaging.notification-exchange", () -> "notification.exchange");
        registry.add("app.messaging.notification-routing-key", () -> "notification.created");
        registry.add("app.messaging.publish-notification", () -> false);
    }

    /**
     * Declara a topologia do history-service ANTES de o contexto do appointment subir,
     * para que a auto-declaracao do appointment tenha de ser compativel com ela.
     */
    @BeforeAll
    static void declararTopologiaDoHistoryService() {
        CachingConnectionFactory factory =
                new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());

        RabbitAdmin admin = new RabbitAdmin(factory);
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).build();

        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY));

        factory.destroy();
    }

    @Test
    void deveEntregarEventoNaFilaDoHistoryService() {
        Appointment appointment = new Appointment();
        appointment.setId(42L);
        appointment.setPatientId(10L);
        appointment.setDoctorId(7L);
        appointment.setAppointmentDate(LocalDateTime.of(2026, 10, 10, 9, 0));
        appointment.setDescription("Consulta de rotina");

        publisher.publish(appointment, AppointmentEventStatus.SCHEDULED);

        Message received = rabbitTemplate.receive(QUEUE, 5000);

        assertThat(received)
                .as("evento publicado pelo appointment deve chegar na fila do history-service")
                .isNotNull();

        String payload = new String(received.getBody(), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"appointmentId\":42");
        assertThat(payload).contains("\"eventStatus\":\"SCHEDULED\"");
    }
}
