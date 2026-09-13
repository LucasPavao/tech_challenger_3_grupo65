package br.com.tech.challenge.notificationservice.service;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.notification.NotificationSender;
import br.com.tech.challenge.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationSender notificationSender;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationRepository, notificationSender);
    }

    @Test
    void deveCriarNotificacaoComoPendingEDepoisMarcarComoSentAoProcessarEventoAgendado() {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AtomicReference<NotificationStatus> statusNoMomentoDoEnvio = new AtomicReference<>();
        doAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            statusNoMomentoDoEnvio.set(n.getStatus());
            return null;
        }).when(notificationSender).send(any(Notification.class));

        Notification result = notificationService.processAppointmentEvent(evento(AppointmentEventStatus.SCHEDULED));

        verify(notificationRepository, times(2)).save(any(Notification.class));
        verify(notificationSender, times(1)).send(any(Notification.class));

        assertThat(statusNoMomentoDoEnvio.get()).isEqualTo(NotificationStatus.PENDING);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(result.getAppointmentId()).isEqualTo(1L);
        assertThat(result.getPatientId()).isEqualTo(10L);
        assertThat(result.getMessage()).contains("agendada");
    }

    @ParameterizedTest
    @CsvSource({
            "SCHEDULED,   agendada",
            "RESCHEDULED, remarcada",
            "CANCELLED,   cancelada",
            "COMPLETED,   realizada"
    })
    void deveGerarMensagemDeAcordoComOStatusDoEvento(AppointmentEventStatus status, String trechoEsperado) {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Notification result = notificationService.processAppointmentEvent(evento(status));

        assertThat(result.getMessage()).contains(trechoEsperado);
    }

    @Test
    void deveGravarEventIdEEventStatusNaNotificacao() {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AppointmentEvent evento = evento(AppointmentEventStatus.RESCHEDULED);

        Notification result = notificationService.processAppointmentEvent(evento);

        assertThat(result.getEventId()).isEqualTo(evento.eventId());
        assertThat(result.getEventStatus()).isEqualTo(AppointmentEventStatus.RESCHEDULED);
    }

    @Test
    void deveManterNotificacaoPendingEPropagarErroQuandoOEnvioFalha() {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("provedor fora do ar"))
                .when(notificationSender).send(any(Notification.class));

        assertThatThrownBy(() -> notificationService.processAppointmentEvent(evento(AppointmentEventStatus.SCHEDULED)))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        // a propagacao rejeita a mensagem, que vai para a DLQ; a notificacao fica gravada como PENDING
        ArgumentCaptor<Notification> salva = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(salva.capture());
        assertThat(salva.getValue().getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void deveBuscarNotificacoesPorPaciente() {
        Long patientId = 10L;
        Notification notification = new Notification();
        notification.setPatientId(patientId);

        when(notificationRepository.findByPatientId(patientId))
                .thenReturn(List.of(notification));

        List<Notification> result = notificationService.findByPatientId(patientId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPatientId()).isEqualTo(patientId);
        verify(notificationRepository, times(1)).findByPatientId(patientId);
    }

    private static AppointmentEvent evento(AppointmentEventStatus status) {
        return new AppointmentEvent(
                UUID.randomUUID(),
                status,
                Instant.parse("2026-09-12T14:00:00Z"),
                1L,
                10L,
                null,
                5L,
                null,
                LocalDateTime.of(2030, 9, 10, 14, 30),
                "Consulta de rotina"
        );
    }
}
