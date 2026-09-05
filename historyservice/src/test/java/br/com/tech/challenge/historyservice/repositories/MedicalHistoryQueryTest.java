package br.com.tech.challenge.historyservice.repositories;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainers.class)
class MedicalHistoryQueryTest {

    @Autowired
    private MedicalHistoryRepository repository;

    private void grava(Long patientId, Long appointmentId, AppointmentEventStatus eventStatus,
                       LocalDateTime appointmentDate, Instant occurredAt) {
        repository.saveAndFlush(MedicalHistory.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(appointmentId)
                .patientId(patientId)
                .patientName("Maria Souza")
                .doctorId(7L)
                .doctorName("Dr. Joao Lima")
                .description("Consulta de rotina")
                .appointmentDate(appointmentDate)
                .eventStatus(eventStatus)
                .occurredAt(occurredAt)
                .build());
    }

    @Test
    void devolveApenasOUltimoEventoDeCadaConsulta() {
        LocalDateTime data = LocalDateTime.of(2026, 9, 5, 9, 0);
        LocalDateTime novaData = LocalDateTime.of(2026, 9, 12, 14, 0);

        // Consulta 42: agendada, remarcada e concluida.
        grava(10L, 42L, AppointmentEventStatus.SCHEDULED, data, Instant.parse("2026-08-30T14:00:00Z"));
        grava(10L, 42L, AppointmentEventStatus.RESCHEDULED, novaData, Instant.parse("2026-08-31T10:00:00Z"));
        grava(10L, 42L, AppointmentEventStatus.COMPLETED, novaData, Instant.parse("2026-09-12T15:00:00Z"));
        // Consulta 58: apenas agendada.
        grava(10L, 58L, AppointmentEventStatus.SCHEDULED, data.plusMonths(1), Instant.parse("2026-09-01T09:00:00Z"));

        List<MedicalHistory> historico = repository.findLatestEventPerAppointment(10L);

        assertThat(historico).hasSize(2);
        assertThat(historico).extracting(MedicalHistory::getAppointmentId)
                .containsExactly(42L, 58L);
        assertThat(historico).extracting(MedicalHistory::getEventStatus)
                .containsExactly(AppointmentEventStatus.COMPLETED, AppointmentEventStatus.SCHEDULED);
        assertThat(historico.getFirst().getAppointmentDate()).isEqualTo(novaData);
    }

    @Test
    void ordenaDaConsultaMaisRecenteParaAMaisAntiga() {
        LocalDateTime data = LocalDateTime.of(2026, 9, 5, 9, 0);
        grava(10L, 10L, AppointmentEventStatus.COMPLETED, data, Instant.parse("2026-08-01T10:00:00Z"));
        grava(10L, 20L, AppointmentEventStatus.SCHEDULED, data, Instant.parse("2026-08-15T10:00:00Z"));
        grava(10L, 30L, AppointmentEventStatus.CANCELLED, data, Instant.parse("2026-08-10T10:00:00Z"));

        assertThat(repository.findLatestEventPerAppointment(10L))
                .extracting(MedicalHistory::getAppointmentId)
                .containsExactly(20L, 30L, 10L);
    }

    @Test
    void naoMisturaPacientes() {
        LocalDateTime data = LocalDateTime.of(2026, 9, 5, 9, 0);
        grava(10L, 42L, AppointmentEventStatus.SCHEDULED, data, Instant.parse("2026-08-30T14:00:00Z"));
        grava(99L, 77L, AppointmentEventStatus.SCHEDULED, data, Instant.parse("2026-08-30T14:00:00Z"));

        assertThat(repository.findLatestEventPerAppointment(10L))
                .extracting(MedicalHistory::getAppointmentId)
                .containsExactly(42L);
    }

    @Test
    void devolveListaVaziaParaPacienteSemHistorico() {
        assertThat(repository.findLatestEventPerAppointment(404L)).isEmpty();
    }
}
