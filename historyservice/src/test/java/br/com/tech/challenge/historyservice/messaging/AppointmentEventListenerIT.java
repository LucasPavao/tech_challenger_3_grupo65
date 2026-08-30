package br.com.tech.challenge.historyservice.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.dto.AppointmentEventDTO;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import br.com.tech.challenge.historyservice.support.RabbitTestcontainers;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({PostgresTestcontainers.class, RabbitTestcontainers.class})
class AppointmentEventListenerIT {

    private static final LocalDateTime DATA_CONSULTA = LocalDateTime.of(2026, 9, 5, 9, 0);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MedicalHistoryRepository repository;

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

    private AppointmentEventDTO evento(UUID eventId, AppointmentEventStatus eventStatus,
                                       LocalDateTime appointmentDate, Instant occurredAt) {
        return new AppointmentEventDTO(
                eventId, eventStatus, occurredAt, 42L, 10L, "Maria Souza",
                7L, "Dr. Joao Lima", appointmentDate, "Consulta de rotina");
    }

    @Test
    void gravaHistoricoAoReceberEventoDaFila() {
        UUID eventId = UUID.randomUUID();

        rabbitTemplate.convertAndSend(exchange, routingKey,
                evento(eventId, AppointmentEventStatus.SCHEDULED, DATA_CONSULTA,
                        Instant.parse("2026-08-30T14:32:10Z")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<MedicalHistory> registros = repository.findByAppointmentIdOrderByOccurredAtAsc(42L);
            assertThat(registros).hasSize(1);
            assertThat(registros.getFirst().getEventId()).isEqualTo(eventId);
            assertThat(registros.getFirst().getEventStatus()).isEqualTo(AppointmentEventStatus.SCHEDULED);
            assertThat(registros.getFirst().getAppointmentDate()).isEqualTo(DATA_CONSULTA);
            assertThat(registros.getFirst().getDoctorName()).isEqualTo("Dr. Joao Lima");
        });
    }

    @Test
    void acumulaOCicloDeVidaDaConsulta() {
        LocalDateTime novaData = LocalDateTime.of(2026, 9, 12, 14, 0);

        rabbitTemplate.convertAndSend(exchange, routingKey,
                evento(UUID.randomUUID(), AppointmentEventStatus.SCHEDULED, DATA_CONSULTA,
                        Instant.parse("2026-08-30T14:00:00Z")));
        rabbitTemplate.convertAndSend(exchange, routingKey,
                evento(UUID.randomUUID(), AppointmentEventStatus.RESCHEDULED, novaData,
                        Instant.parse("2026-08-30T15:00:00Z")));
        rabbitTemplate.convertAndSend(exchange, routingKey,
                evento(UUID.randomUUID(), AppointmentEventStatus.COMPLETED, novaData,
                        Instant.parse("2026-09-12T17:00:00Z")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<MedicalHistory> trilha = repository.findByAppointmentIdOrderByOccurredAtAsc(42L);
            assertThat(trilha).hasSize(3);
            assertThat(trilha).extracting(MedicalHistory::getEventStatus)
                    .containsExactly(AppointmentEventStatus.SCHEDULED,
                            AppointmentEventStatus.RESCHEDULED,
                            AppointmentEventStatus.COMPLETED);
            assertThat(trilha).extracting(MedicalHistory::getAppointmentDate)
                    .containsExactly(DATA_CONSULTA, novaData, novaData);
        });
    }

    @Test
    void naoDuplicaQuandoOMesmoEventoChegaDuasVezes() {
        UUID eventId = UUID.randomUUID();
        AppointmentEventDTO mesmoEvento = evento(eventId, AppointmentEventStatus.SCHEDULED,
                DATA_CONSULTA, Instant.parse("2026-08-30T14:32:10Z"));

        rabbitTemplate.convertAndSend(exchange, routingKey, mesmoEvento);
        rabbitTemplate.convertAndSend(exchange, routingKey, mesmoEvento);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.findByAppointmentIdOrderByOccurredAtAsc(42L)).hasSize(1));

        // Confirma que a contagem se mantem estavel e nao e apenas um resultado transitorio.
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.findByAppointmentIdOrderByOccurredAtAsc(42L)).hasSize(1));
    }

    @Test
    void mandaParaDlqEventoComCampoDesconhecido() {
        enviaJsonCru("""
                {
                  "eventId": "%s",
                  "eventStatus": "SCHEDULED",
                  "occurredAt": "2026-08-30T14:32:10Z",
                  "appointmentId": 99,
                  "patientId": 10,
                  "doctorId": 7,
                  "appointmentDate": "2026-09-05T09:00:00",
                  "campoInesperado": true
                }
                """.formatted(UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive(queue + ".dlq")).isNotNull());

        assertThat(repository.findByAppointmentIdOrderByOccurredAtAsc(99L)).isEmpty();
    }

    @Test
    void mandaParaDlqEventoSemAppointmentDate() {
        enviaJsonCru("""
                {
                  "eventId": "%s",
                  "eventStatus": "CANCELLED",
                  "occurredAt": "2026-08-30T14:32:10Z",
                  "appointmentId": 77,
                  "patientId": 10,
                  "doctorId": 7
                }
                """.formatted(UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive(queue + ".dlq")).isNotNull());

        assertThat(repository.findByAppointmentIdOrderByOccurredAtAsc(77L)).isEmpty();
    }

    private void enviaJsonCru(String payload) {
        Message mensagem = MessageBuilder
                .withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        rabbitTemplate.send(exchange, routingKey, mensagem);
    }
}
