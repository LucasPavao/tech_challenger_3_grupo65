package br.com.tech.challenge.historyservice.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentStatus;
import br.com.tech.challenge.historyservice.domain.EventType;
import br.com.tech.challenge.historyservice.dto.AppointmentEventDTO;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryIngestionServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-30T14:32:10Z");

    private static ValidatorFactory validatorFactory;

    private MedicalHistoryRepository repository;
    private HistoryIngestionService service;

    @BeforeAll
    static void abrirValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void fecharValidatorFactory() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        repository = mock(MedicalHistoryRepository.class);
        Validator validator = validatorFactory.getValidator();
        service = new HistoryIngestionService(repository, validator);
    }

    private AppointmentEventDTO evento(String patientName, String doctorName) {
        return new AppointmentEventDTO(
                EVENT_ID, EventType.CREATED, OCCURRED_AT, 42L, 10L, patientName,
                7L, doctorName, LocalDateTime.of(2026, 9, 5, 9, 0),
                "Consulta de rotina", AppointmentStatus.SCHEDULED);
    }

    @Test
    void mapeiaTodosOsCamposDoEventoParaAEntidade() {
        service.ingest(evento("Maria Souza", "Dr. Joao Lima"));

        ArgumentCaptor<MedicalHistory> captor = ArgumentCaptor.forClass(MedicalHistory.class);
        verify(repository).save(captor.capture());
        MedicalHistory salvo = captor.getValue();

        assertThat(salvo.getEventId()).isEqualTo(EVENT_ID);
        assertThat(salvo.getEventType()).isEqualTo(EventType.CREATED);
        assertThat(salvo.getOccurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(salvo.getAppointmentId()).isEqualTo(42L);
        assertThat(salvo.getPatientId()).isEqualTo(10L);
        assertThat(salvo.getPatientName()).isEqualTo("Maria Souza");
        assertThat(salvo.getDoctorId()).isEqualTo(7L);
        assertThat(salvo.getDoctorName()).isEqualTo("Dr. Joao Lima");
        assertThat(salvo.getDateTime()).isEqualTo(LocalDateTime.of(2026, 9, 5, 9, 0));
        assertThat(salvo.getDescription()).isEqualTo("Consulta de rotina");
        assertThat(salvo.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
    }

    @Test
    void propagaNomesNulos() {
        service.ingest(evento(null, null));

        ArgumentCaptor<MedicalHistory> captor = ArgumentCaptor.forClass(MedicalHistory.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getPatientName()).isNull();
        assertThat(captor.getValue().getDoctorName()).isNull();
    }

    @Test
    void ignoraEventoJaProcessado() {
        when(repository.existsByEventId(EVENT_ID)).thenReturn(true);

        service.ingest(evento("Maria Souza", "Dr. Joao Lima"));

        verify(repository, never()).save(any());
    }

    @Test
    void engoleColisaoDeEventIdConcorrente() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("uk_medical_history_event_id"));

        assertThatCode(() -> service.ingest(evento("Maria Souza", "Dr. Joao Lima")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejeitaEventoSemCampoObrigatorio() {
        AppointmentEventDTO semStatus = new AppointmentEventDTO(
                EVENT_ID, EventType.CREATED, OCCURRED_AT, 42L, 10L, "Maria Souza",
                7L, "Dr. Joao Lima", LocalDateTime.of(2026, 9, 5, 9, 0),
                "Consulta de rotina", null);

        assertThatThrownBy(() -> service.ingest(semStatus))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("status");

        verify(repository, never()).save(any());
    }

    @Test
    void rejeitaEventoNulo() {
        assertThatThrownBy(() -> service.ingest(null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }
}
