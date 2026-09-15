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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

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

        MedicalRecordResponse resposta = service.patientHistory(10L, null).getFirst();

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

        MedicalRecordResponse resposta = service.patientHistory(10L, null).getFirst();

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

        MedicalRecordResponse resposta = service.patientHistory(10L, null).getFirst();

        assertThat(resposta.patientName()).isNull();
        assertThat(resposta.doctorName()).isNull();
        assertThat(resposta.description()).isNull();
    }

    @Test
    void devolveListaVaziaQuandoNaoHaHistorico() {
        when(repository.findLatestEventPerAppointment(404L)).thenReturn(List.of());

        assertThat(service.patientHistory(404L, null)).isEmpty();
    }

    @Test
    void rejeitaPatientIdNulo() {
        assertThatThrownBy(() -> service.patientHistory(null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).findLatestEventPerAppointment(any());
    }

    @Test
    void appointmentTimelineDevolveATrilhaEmOrdemCronologica() {
        MedicalHistory agendada = MedicalHistory.builder()
                .eventId(UUID.randomUUID()).appointmentId(42L).patientId(10L).doctorId(7L)
                .appointmentDate(LocalDateTime.of(2026, 9, 5, 9, 0))
                .eventStatus(AppointmentEventStatus.SCHEDULED)
                .occurredAt(Instant.parse("2026-08-30T14:00:00Z")).build();
        MedicalHistory remarcada = MedicalHistory.builder()
                .eventId(UUID.randomUUID()).appointmentId(42L).patientId(10L).doctorId(7L)
                .appointmentDate(LocalDateTime.of(2026, 9, 12, 14, 0))
                .eventStatus(AppointmentEventStatus.RESCHEDULED)
                .occurredAt(Instant.parse("2026-08-31T10:00:00Z")).build();
        when(repository.findByAppointmentIdOrderByOccurredAtAscIdAsc(42L))
                .thenReturn(List.of(agendada, remarcada));

        List<MedicalRecordResponse> trilha = service.appointmentTimeline(42L);

        assertThat(trilha).extracting(MedicalRecordResponse::eventStatus)
                .containsExactly(AppointmentEventStatus.SCHEDULED, AppointmentEventStatus.RESCHEDULED);
        assertThat(trilha).extracting(MedicalRecordResponse::appointmentDate)
                .containsExactly("2026-09-05T09:00:00", "2026-09-12T14:00:00");
    }

    @Test
    void appointmentTimelineDevolveListaVaziaParaConsultaDesconhecida() {
        when(repository.findByAppointmentIdOrderByOccurredAtAscIdAsc(404L)).thenReturn(List.of());

        assertThat(service.appointmentTimeline(404L)).isEmpty();
    }

    @Test
    void appointmentTimelineRejeitaAppointmentIdNulo() {
        assertThatThrownBy(() -> service.appointmentTimeline(null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).findByAppointmentIdOrderByOccurredAtAscIdAsc(any());
    }

    private JwtAuthenticationToken token(String role, long userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("user_id", userId)
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void pacienteConsultaOProprioHistorico() {
        when(repository.findLatestEventPerAppointment(10L)).thenReturn(List.of(registro()));

        assertThat(service.patientHistory(10L, token("ROLE_PATIENT", 10L))).hasSize(1);
    }

    @Test
    void pacienteNaoConsultaHistoricoDeOutroPaciente() {
        assertThatThrownBy(() -> service.patientHistory(11L, token("ROLE_PATIENT", 10L)))
                .isInstanceOf(AccessDeniedException.class);

        verify(repository, never()).findLatestEventPerAppointment(any());
    }

    @Test
    void enfermeiraConsultaHistoricoDeQualquerPaciente() {
        when(repository.findLatestEventPerAppointment(11L)).thenReturn(List.of(registro()));

        assertThat(service.patientHistory(11L, token("ROLE_NURSE", 3L))).hasSize(1);
    }
}
