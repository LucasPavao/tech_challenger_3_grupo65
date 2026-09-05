package br.com.tech.challenge.historyservice.graphql;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.dto.AppointmentEventDTO;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import br.com.tech.challenge.historyservice.support.RabbitTestcontainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Fluxo completo: evento publicado no RabbitMQ real -> gravado no Postgres real -> lido por
 * GraphQL sobre HTTP. Nada mockado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Import({PostgresTestcontainers.class, RabbitTestcontainers.class})
class HistoryGraphQlIT {

    private static final LocalDateTime DATA_ORIGINAL = LocalDateTime.of(2026, 9, 5, 9, 0);
    private static final LocalDateTime NOVA_DATA = LocalDateTime.of(2026, 9, 12, 14, 0);

    @Autowired
    private HttpGraphQlTester graphQlTester;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MedicalHistoryRepository repository;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    @BeforeEach
    void limparBase() {
        repository.deleteAll();
    }

    private void publica(AppointmentEventStatus eventStatus, LocalDateTime appointmentDate, Instant occurredAt) {
        rabbitTemplate.convertAndSend(exchange, routingKey, new AppointmentEventDTO(
                UUID.randomUUID(), eventStatus, occurredAt, 42L, 10L, "Maria Souza",
                7L, "Dr. Joao Lima", appointmentDate, "Consulta de rotina"));
    }

    @Test
    void eventoPublicadoNaFilaApareceNoPatientHistory() {
        publica(AppointmentEventStatus.SCHEDULED, DATA_ORIGINAL, Instant.parse("2026-08-30T14:00:00Z"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.findLatestEventPerAppointment(10L)).hasSize(1));

        graphQlTester.document("""
                        query {
                          patientHistory(patientId: 10) {
                            appointmentId
                            eventStatus
                            appointmentDate
                            doctorName
                          }
                        }
                        """)
                .execute()
                .path("patientHistory[0].appointmentId").entity(String.class).isEqualTo("42")
                .path("patientHistory[0].eventStatus").entity(String.class).isEqualTo("SCHEDULED")
                .path("patientHistory[0].appointmentDate").entity(String.class).isEqualTo("2026-09-05T09:00:00")
                .path("patientHistory[0].doctorName").entity(String.class).isEqualTo("Dr. Joao Lima");
    }

    @Test
    void patientHistoryMostraApenasOEstadoAtualDepoisDeRemarcar() {
        publica(AppointmentEventStatus.SCHEDULED, DATA_ORIGINAL, Instant.parse("2026-08-30T14:00:00Z"));
        publica(AppointmentEventStatus.RESCHEDULED, NOVA_DATA, Instant.parse("2026-08-31T10:00:00Z"));
        publica(AppointmentEventStatus.COMPLETED, NOVA_DATA, Instant.parse("2026-09-12T15:00:00Z"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.findByAppointmentIdOrderByOccurredAtAscIdAsc(42L)).hasSize(3));

        graphQlTester.document("{ patientHistory(patientId: 10) { appointmentId eventStatus appointmentDate } }")
                .execute()
                .path("patientHistory").entityList(Object.class).hasSize(1)
                .path("patientHistory[0].eventStatus").entity(String.class).isEqualTo("COMPLETED")
                .path("patientHistory[0].appointmentDate").entity(String.class).isEqualTo("2026-09-12T14:00:00");
    }

    @Test
    void appointmentTimelineMostraATrilhaInteiraComADataAntiga() {
        publica(AppointmentEventStatus.SCHEDULED, DATA_ORIGINAL, Instant.parse("2026-08-30T14:00:00Z"));
        publica(AppointmentEventStatus.RESCHEDULED, NOVA_DATA, Instant.parse("2026-08-31T10:00:00Z"));
        publica(AppointmentEventStatus.COMPLETED, NOVA_DATA, Instant.parse("2026-09-12T15:00:00Z"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(repository.findByAppointmentIdOrderByOccurredAtAscIdAsc(42L)).hasSize(3));

        graphQlTester.document("{ appointmentTimeline(appointmentId: 42) { eventStatus appointmentDate } }")
                .execute()
                .path("appointmentTimeline").entityList(Object.class).hasSize(3)
                .path("appointmentTimeline[0].eventStatus").entity(String.class).isEqualTo("SCHEDULED")
                .path("appointmentTimeline[0].appointmentDate").entity(String.class).isEqualTo("2026-09-05T09:00:00")
                .path("appointmentTimeline[1].eventStatus").entity(String.class).isEqualTo("RESCHEDULED")
                .path("appointmentTimeline[2].eventStatus").entity(String.class).isEqualTo("COMPLETED");
    }

    @Test
    void pacienteSemHistoricoDevolveListaVazia() {
        graphQlTester.document("{ patientHistory(patientId: 404) { appointmentId } }")
                .execute()
                .errors().verify()
                .path("patientHistory").entityList(Object.class).hasSize(0);
    }
}
