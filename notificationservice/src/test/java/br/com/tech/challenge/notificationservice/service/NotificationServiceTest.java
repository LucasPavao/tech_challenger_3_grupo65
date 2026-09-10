package br.com.tech.challenge.notificationservice.service;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.dto.EventType;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.notification.NotificationSender;
import br.com.tech.challenge.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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
    void deveCriarNotificacaoComoPendingEDepoisMarcarComoSentAoProcessarEventoDeCriacao() {
        AppointmentEvent event = new AppointmentEvent();
        event.setAppointmentId(1L);
        event.setPatientId(10L);
        event.setDoctorId(5L);
        event.setDateTime(LocalDateTime.of(2026, 9, 10, 14, 30));
        event.setDescription("Consulta de rotina");
        event.setEventType(EventType.CREATED);

        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AtomicReference<NotificationStatus> statusNoMomentoDoEnvio = new AtomicReference<>();
        doAnswer(invocation -> {
            Notification n = invocation.getArgument(0);
            statusNoMomentoDoEnvio.set(n.getStatus());
            return null;
        }).when(notificationSender).send(any(Notification.class));

        Notification result = notificationService.processAppointmentEvent(event);

        verify(notificationRepository, times(2)).save(any(Notification.class));
        verify(notificationSender, times(1)).send(any(Notification.class));

        assertThat(statusNoMomentoDoEnvio.get()).isEqualTo(NotificationStatus.PENDING);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(result.getAppointmentId()).isEqualTo(1L);
        assertThat(result.getPatientId()).isEqualTo(10L);
        assertThat(result.getMessage()).contains("agendada");
    }

    @Test
    void deveGerarMensagemDiferenteParaEventoDeAtualizacao() {
        AppointmentEvent event = new AppointmentEvent();
        event.setAppointmentId(2L);
        event.setPatientId(20L);
        event.setDoctorId(7L);
        event.setDateTime(LocalDateTime.of(2026, 10, 1, 9, 0));
        event.setEventType(EventType.UPDATED);

        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Notification result = notificationService.processAppointmentEvent(event);

        assertThat(result.getMessage()).contains("atualizada");
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
}
