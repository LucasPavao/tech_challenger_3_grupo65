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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testes mínimos exigidos pelo plano de divisão para a Pessoa 5:
 * recebimento do evento, criação e processamento da notificação.
 */
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

        // save() é chamado duas vezes: 1) ao persistir como PENDING, 2) ao persistir como SENT
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Notification result = notificationService.processAppointmentEvent(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        verify(notificationSender, times(1)).send(any(Notification.class));

        Notification firstSave = captor.getAllValues().get(0);
        assertThat(firstSave.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(firstSave.getAppointmentId()).isEqualTo(1L);
        assertThat(firstSave.getPatientId()).isEqualTo(10L);
        assertThat(firstSave.getMessage()).contains("agendada");

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SENT);
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
