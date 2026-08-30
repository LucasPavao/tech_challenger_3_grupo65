package br.com.tech.challenge.historyservice.repositories;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentStatus;
import br.com.tech.challenge.historyservice.domain.EventType;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainers.class)
class MedicalHistoryRepositoryTest {

    @Autowired
    private MedicalHistoryRepository repository;

    private MedicalHistory registro(UUID eventId, Long appointmentId, AppointmentStatus status, Instant occurredAt) {
        return MedicalHistory.builder()
                .eventId(eventId)
                .appointmentId(appointmentId)
                .patientId(10L)
                .patientName("Maria Souza")
                .doctorId(7L)
                .doctorName("Dr. Joao Lima")
                .description("Consulta de rotina")
                .dateTime(LocalDateTime.of(2026, 9, 5, 9, 0))
                .status(status)
                .eventType(EventType.CREATED)
                .occurredAt(occurredAt)
                .build();
    }

    @Test
    void persisteERecuperaTodosOsCampos() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-30T14:32:10Z");

        MedicalHistory salvo = repository.saveAndFlush(
                registro(eventId, 42L, AppointmentStatus.SCHEDULED, occurredAt));

        assertThat(salvo.getId()).isNotNull();
        assertThat(salvo.getRecordedAt()).isNotNull();
        assertThat(salvo.getEventId()).isEqualTo(eventId);
        assertThat(salvo.getAppointmentId()).isEqualTo(42L);
        assertThat(salvo.getPatientId()).isEqualTo(10L);
        assertThat(salvo.getPatientName()).isEqualTo("Maria Souza");
        assertThat(salvo.getDoctorId()).isEqualTo(7L);
        assertThat(salvo.getDoctorName()).isEqualTo("Dr. Joao Lima");
        assertThat(salvo.getDescription()).isEqualTo("Consulta de rotina");
        assertThat(salvo.getDateTime()).isEqualTo(LocalDateTime.of(2026, 9, 5, 9, 0));
        assertThat(salvo.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(salvo.getEventType()).isEqualTo(EventType.CREATED);
        assertThat(salvo.getOccurredAt()).isEqualTo(occurredAt);
    }

    @Test
    void aceitaNomesNulos() {
        MedicalHistory semNomes = MedicalHistory.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(42L)
                .patientId(10L)
                .doctorId(7L)
                .dateTime(LocalDateTime.of(2026, 9, 5, 9, 0))
                .status(AppointmentStatus.CANCELLED)
                .eventType(EventType.UPDATED)
                .occurredAt(Instant.parse("2026-08-30T14:32:10Z"))
                .build();

        MedicalHistory salvo = repository.saveAndFlush(semNomes);

        assertThat(salvo.getPatientName()).isNull();
        assertThat(salvo.getDoctorName()).isNull();
        assertThat(salvo.getDescription()).isNull();
    }

    @Test
    void rejeitaEventIdDuplicado() {
        UUID mesmoEventId = UUID.randomUUID();
        repository.saveAndFlush(registro(mesmoEventId, 42L, AppointmentStatus.SCHEDULED,
                Instant.parse("2026-08-30T14:32:10Z")));

        assertThatThrownBy(() -> repository.saveAndFlush(
                registro(mesmoEventId, 42L, AppointmentStatus.SCHEDULED,
                        Instant.parse("2026-08-30T14:32:10Z"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acumulaVariasLinhasParaOMesmoAppointment() {
        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentStatus.SCHEDULED,
                Instant.parse("2026-08-30T14:00:00Z")));
        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentStatus.COMPLETED,
                Instant.parse("2026-08-30T15:00:00Z")));

        List<MedicalHistory> trilha = repository.findByAppointmentIdOrderByOccurredAtAsc(42L);

        assertThat(trilha).hasSize(2);
        assertThat(trilha).extracting(MedicalHistory::getStatus)
                .containsExactly(AppointmentStatus.SCHEDULED, AppointmentStatus.COMPLETED);
    }

    @Test
    void existsByEventIdEncontraRegistroGravado() {
        UUID eventId = UUID.randomUUID();
        repository.saveAndFlush(registro(eventId, 42L, AppointmentStatus.SCHEDULED,
                Instant.parse("2026-08-30T14:32:10Z")));

        assertThat(repository.existsByEventId(eventId)).isTrue();
        assertThat(repository.existsByEventId(UUID.randomUUID())).isFalse();
    }
}
