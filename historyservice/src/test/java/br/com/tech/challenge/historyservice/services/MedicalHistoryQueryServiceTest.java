package br.com.tech.challenge.historyservice.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.dto.MedicalRecordResponse;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicalHistoryQueryServiceTest {

    private MedicalHistoryRepository repository;
    private MedicalHistoryQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(MedicalHistoryRepository.class);
        service = new MedicalHistoryQueryService(repository);
    }

    private MedicalHistory registro() {
        return MedicalHistory.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(42L)
                .patientId(10L)
                .patientName("Maria Souza")
                .doctorId(7L)
                .doctorName("Dr. Joao Lima")
                .description("Consulta de rotina")
                .appointmentDate(LocalDateTime.of(2026, 9, 5, 9, 0))
                .eventStatus(AppointmentEventStatus.SCHEDULED)
                .occurredAt(Instant.parse("2026-08-30T14:32:10Z"))
                .build();
    }

    @Test
    void converteAEntidadeParaAResposta() {
        when(repository.findLatestEventPerAppointment(10L)).thenReturn(List.of(registro()));

        MedicalRecordResponse resposta = service.patientHistory(10L).getFirst();

        assertThat(resposta.appointmentId()).isEqualTo("42");
        assertThat(resposta.patientId()).isEqualTo("10");
        assertThat(resposta.patientName()).isEqualTo("Maria Souza");
        assertThat(resposta.doctorId()).isEqualTo("7");
        assertThat(resposta.doctorName()).isEqualTo("Dr. Joao Lima");
        assertThat(resposta.description()).isEqualTo("Consulta de rotina");
        assertThat(resposta.eventStatus()).isEqualTo(AppointmentEventStatus.SCHEDULED);
    }

    @Test
    void formataAsDatasComoIso8601() {
        when(repository.findLatestEventPerAppointment(10L)).thenReturn(List.of(registro()));

        MedicalRecordResponse resposta = service.patientHistory(10L).getFirst();

        assertThat(resposta.appointmentDate()).isEqualTo("2026-09-05T09:00:00");
        assertThat(resposta.occurredAt()).isEqualTo("2026-08-30T14:32:10Z");
    }

    @Test
    void propagaNomesNulos() {
        MedicalHistory semNomes = MedicalHistory.builder()
                .eventId(UUID.randomUUID())
                .appointmentId(42L)
                .patientId(10L)
                .doctorId(7L)
                .appointmentDate(LocalDateTime.of(2026, 9, 5, 9, 0))
                .eventStatus(AppointmentEventStatus.CANCELLED)
                .occurredAt(Instant.parse("2026-08-30T14:32:10Z"))
                .build();
        when(repository.findLatestEventPerAppointment(10L)).thenReturn(List.of(semNomes));

        MedicalRecordResponse resposta = service.patientHistory(10L).getFirst();

        assertThat(resposta.patientName()).isNull();
        assertThat(resposta.doctorName()).isNull();
        assertThat(resposta.description()).isNull();
    }

    @Test
    void devolveListaVaziaQuandoNaoHaHistorico() {
        when(repository.findLatestEventPerAppointment(404L)).thenReturn(List.of());

        assertThat(service.patientHistory(404L)).isEmpty();
    }

    @Test
    void rejeitaPatientIdNulo() {
        assertThatThrownBy(() -> service.patientHistory(null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).findLatestEventPerAppointment(any());
    }
}
