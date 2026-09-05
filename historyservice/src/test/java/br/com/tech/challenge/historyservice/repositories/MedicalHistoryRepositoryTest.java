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
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainers.class)
class MedicalHistoryRepositoryTest {

    private static final LocalDateTime DATA_CONSULTA = LocalDateTime.of(2026, 9, 5, 9, 0);

    @Autowired
    private MedicalHistoryRepository repository;

    private MedicalHistory registro(UUID eventId, Long appointmentId, AppointmentEventStatus eventStatus,
                                    LocalDateTime appointmentDate, Instant occurredAt) {
        return MedicalHistory.builder()
                .eventId(eventId)
                .appointmentId(appointmentId)
                .patientId(10L)
                .patientName("Maria Souza")
                .doctorId(7L)
                .doctorName("Dr. Joao Lima")
                .description("Consulta de rotina")
                .appointmentDate(appointmentDate)
                .eventStatus(eventStatus)
                .occurredAt(occurredAt)
                .build();
    }

    @Test
    void persisteERecuperaTodosOsCampos() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-30T14:32:10Z");

        MedicalHistory salvo = repository.saveAndFlush(
                registro(eventId, 42L, AppointmentEventStatus.SCHEDULED, DATA_CONSULTA, occurredAt));

        assertThat(salvo.getId()).isNotNull();
        assertThat(salvo.getRecordedAt()).isNotNull();
        assertThat(salvo.getEventId()).isEqualTo(eventId);
        assertThat(salvo.getAppointmentId()).isEqualTo(42L);
        assertThat(salvo.getPatientId()).isEqualTo(10L);
        assertThat(salvo.getPatientName()).isEqualTo("Maria Souza");
        assertThat(salvo.getDoctorId()).isEqualTo(7L);
        assertThat(salvo.getDoctorName()).isEqualTo("Dr. Joao Lima");
        assertThat(salvo.getDescription()).isEqualTo("Consulta de rotina");
        assertThat(salvo.getAppointmentDate()).isEqualTo(DATA_CONSULTA);
        assertThat(salvo.getEventStatus()).isEqualTo(AppointmentEventStatus.SCHEDULED);
        assertThat(salvo.getOccurredAt()).isEqualTo(occurredAt);
    }

    @Test
    void aceitaNomesEDescricaoNulos() {
        MedicalHistory semNomes = MedicalHistory.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(42L)
                .patientId(10L)
                .doctorId(7L)
                .appointmentDate(DATA_CONSULTA)
                .eventStatus(AppointmentEventStatus.CANCELLED)
                .occurredAt(Instant.parse("2026-08-30T14:32:10Z"))
                .build();

        MedicalHistory salvo = repository.saveAndFlush(semNomes);

        assertThat(salvo.getPatientName()).isNull();
        assertThat(salvo.getDoctorName()).isNull();
        assertThat(salvo.getDescription()).isNull();
    }

    @Test
    void rejeitaAppointmentDateNulo() {
        MedicalHistory semData = MedicalHistory.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(42L)
                .patientId(10L)
                .doctorId(7L)
                .appointmentDate(null)
                .eventStatus(AppointmentEventStatus.CANCELLED)
                .occurredAt(Instant.parse("2026-08-30T14:32:10Z"))
                .build();

        assertThatThrownBy(() -> repository.saveAndFlush(semData))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejeitaEventIdDuplicado() {
        UUID mesmoEventId = UUID.randomUUID();
        repository.saveAndFlush(registro(mesmoEventId, 42L, AppointmentEventStatus.SCHEDULED,
                DATA_CONSULTA, Instant.parse("2026-08-30T14:32:10Z")));

        assertThatThrownBy(() -> repository.saveAndFlush(
                registro(mesmoEventId, 42L, AppointmentEventStatus.SCHEDULED,
                        DATA_CONSULTA, Instant.parse("2026-08-30T14:32:10Z"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void guardaADataAnteriorAoRemarcar() {
        LocalDateTime novaData = LocalDateTime.of(2026, 9, 12, 14, 0);

        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentEventStatus.SCHEDULED,
                DATA_CONSULTA, Instant.parse("2026-08-30T14:00:00Z")));
        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentEventStatus.RESCHEDULED,
                novaData, Instant.parse("2026-08-30T15:00:00Z")));

        List<MedicalHistory> trilha = repository.findByAppointmentIdOrderByOccurredAtAscIdAsc(42L);

        assertThat(trilha).hasSize(2);
        assertThat(trilha).extracting(MedicalHistory::getEventStatus)
                .containsExactly(AppointmentEventStatus.SCHEDULED, AppointmentEventStatus.RESCHEDULED);
        assertThat(trilha).extracting(MedicalHistory::getAppointmentDate)
                .containsExactly(DATA_CONSULTA, novaData);
    }

    @Test
    void acumulaOCicloDeVidaCompletoDaConsulta() {
        Instant t = Instant.parse("2026-08-30T14:00:00Z");
        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentEventStatus.SCHEDULED,
                DATA_CONSULTA, t));
        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentEventStatus.RESCHEDULED,
                DATA_CONSULTA.plusDays(7), t.plusSeconds(3600)));
        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentEventStatus.COMPLETED,
                DATA_CONSULTA.plusDays(7), t.plusSeconds(7200)));

        assertThat(repository.findByAppointmentIdOrderByOccurredAtAscIdAsc(42L))
                .extracting(MedicalHistory::getEventStatus)
                .containsExactly(AppointmentEventStatus.SCHEDULED,
                        AppointmentEventStatus.RESCHEDULED,
                        AppointmentEventStatus.COMPLETED);
    }

    @Test
    void trilhaMantemAOrdemDeIngestaoQuandoOccurredAtEmpata() {
        Instant mesmoInstante = Instant.parse("2026-08-30T14:00:00Z");

        // Mesmo occurred_at, event_id distintos: sem desempate por id a ordem seria arbitraria.
        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentEventStatus.SCHEDULED,
                DATA_CONSULTA, mesmoInstante));
        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentEventStatus.RESCHEDULED,
                DATA_CONSULTA, mesmoInstante));
        repository.saveAndFlush(registro(UUID.randomUUID(), 42L, AppointmentEventStatus.COMPLETED,
                DATA_CONSULTA, mesmoInstante));

        assertThat(repository.findByAppointmentIdOrderByOccurredAtAscIdAsc(42L))
                .extracting(MedicalHistory::getEventStatus)
                .containsExactly(AppointmentEventStatus.SCHEDULED,
                        AppointmentEventStatus.RESCHEDULED,
                        AppointmentEventStatus.COMPLETED);
    }

    @Test
    void existsByEventIdEncontraRegistroGravado() {
        UUID eventId = UUID.randomUUID();
        repository.saveAndFlush(registro(eventId, 42L, AppointmentEventStatus.SCHEDULED,
                DATA_CONSULTA, Instant.parse("2026-08-30T14:32:10Z")));

        assertThat(repository.existsByEventId(eventId)).isTrue();
        assertThat(repository.existsByEventId(UUID.randomUUID())).isFalse();
    }
}
