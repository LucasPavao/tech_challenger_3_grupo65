package br.com.tech.challenge.notificationservice.messaging;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.repository.NotificationRepository;
import br.com.tech.challenge.notificationservice.support.PostgresTestcontainers;
import br.com.tech.challenge.notificationservice.support.RabbitTestcontainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({PostgresTestcontainers.class, RabbitTestcontainers.class})
class NotificationMessageListenerIT {

    private static final LocalDateTime DATA_CONSULTA = LocalDateTime.of(2030, 9, 10, 14, 30);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private NotificationRepository repository;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    @Value("${app.rabbitmq.queue}")
    private String queue;

    @BeforeEach
    void limparBase() {
        repository.deleteAll();
        while (rabbitTemplate.receive(queue + ".dlq") != null) {
            // drena a DLQ para nao herdar mensagens de outro teste
        }
    }

    private AppointmentEvent evento(UUID eventId, AppointmentEventStatus status, long patientId) {
        return new AppointmentEvent(
                eventId, status, Instant.parse("2026-09-12T14:00:00Z"), 42L, patientId,
                "Maria Souza", 7L, "Dr. Joao Lima", DATA_CONSULTA, "Consulta de rotina");
    }

    @Test
    void criaNotificacaoEnviadaAoReceberEventoDaFila() {
        UUID eventId = UUID.randomUUID();

        rabbitTemplate.convertAndSend(exchange, routingKey, evento(eventId, AppointmentEventStatus.SCHEDULED, 10L));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Optional<Notification> gravada = repository.findByEventId(eventId);
            assertThat(gravada).isPresent();
            assertThat(gravada.get().getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(gravada.get().getEventStatus()).isEqualTo(AppointmentEventStatus.SCHEDULED);
            assertThat(gravada.get().getPatientId()).isEqualTo(10L);
            assertThat(gravada.get().getMessage()).contains("agendada");
        });
    }

    @Test
    void naoDuplicaQuandoOMesmoEventoChegaDuasVezes() {
        AppointmentEvent mesmoEvento = evento(UUID.randomUUID(), AppointmentEventStatus.SCHEDULED, 11L);

        rabbitTemplate.convertAndSend(exchange, routingKey, mesmoEvento);
        rabbitTemplate.convertAndSend(exchange, routingKey, mesmoEvento);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.findByPatientId(11L)).hasSize(1));
        // da tempo de a segunda entrega ser processada antes de concluir que nao duplicou
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.findByPatientId(11L)).hasSize(1));
    }

    @Test
    void mandaParaDlqEventoComCampoDesconhecido() {
        enviaJsonCru("""
                {
                  "eventId": "%s",
                  "eventStatus": "SCHEDULED",
                  "occurredAt": "2026-09-12T14:00:00Z",
                  "appointmentId": 99,
                  "patientId": 12,
                  "doctorId": 7,
                  "appointmentDate": "2030-09-10T14:30:00",
                  "campoInesperado": true
                }
                """.formatted(UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive(queue + ".dlq")).isNotNull());

        assertThat(repository.findByPatientId(12L)).isEmpty();
    }

    @Test
    void mandaParaDlqEventoSemAppointmentDate() {
        enviaJsonCru("""
                {
                  "eventId": "%s",
                  "eventStatus": "CANCELLED",
                  "occurredAt": "2026-09-12T14:00:00Z",
                  "appointmentId": 77,
                  "patientId": 13,
                  "doctorId": 7
                }
                """.formatted(UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive(queue + ".dlq")).isNotNull());

        assertThat(repository.findByPatientId(13L)).isEmpty();
    }

    private void enviaJsonCru(String payload) {
        Message mensagem = MessageBuilder
                .withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        rabbitTemplate.send(exchange, routingKey, mensagem);
    }
}
