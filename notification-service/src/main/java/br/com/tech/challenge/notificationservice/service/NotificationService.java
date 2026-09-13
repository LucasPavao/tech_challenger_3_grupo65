package br.com.tech.challenge.notificationservice.service;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.notification.NotificationSender;
import br.com.tech.challenge.notificationservice.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationSender notificationSender) {

        this.notificationRepository = notificationRepository;
        this.notificationSender = notificationSender;
    }

    /**
     * Processa um evento de alteração de consulta e envia uma notificação ao paciente.
     *
     * @param event Evento contendo informações sobre a alteração na consulta
     * @return Notificação criada e processada
     */
    public Notification processAppointmentEvent(AppointmentEvent event) {
        log.debug("Processando evento de appointment: appointmentId={}, status={}",
                event.appointmentId(), event.eventStatus());

        Notification notification = createNotification(event);

        Notification saved = notificationRepository.save(notification);
        log.debug("Notificação criada: id={}, appointmentId={}", saved.getId(), saved.getAppointmentId());

        try {
            notificationSender.send(saved);
            saved.setStatus(NotificationStatus.SENT);
            log.info("Notificação enviada: id={}, appointmentId={}", saved.getId(), saved.getAppointmentId());
        } catch (Exception e) {
            log.error("Erro ao enviar notificação: id={}, appointmentId={}. Erro: {}",
                    saved.getId(), saved.getAppointmentId(), e.getMessage());
            // Mantém o status como PENDING para eventual reprocessamento
            throw new RuntimeException("Falha ao enviar notificação", e);
        }

        return notificationRepository.save(saved);
    }

    private Notification createNotification(AppointmentEvent event) {
        Notification notification = new Notification();

        notification.setEventId(event.eventId());
        notification.setEventStatus(event.eventStatus());
        notification.setAppointmentId(event.appointmentId());
        notification.setPatientId(event.patientId());
        notification.setMessage(createMessage(event));
        notification.setCreatedAt(LocalDateTime.now());
        notification.setStatus(NotificationStatus.PENDING);

        return notification;
    }

    private String createMessage(AppointmentEvent event) {
        return switch (event.eventStatus()) {
            case SCHEDULED ->
                    "Sua consulta foi agendada para " + event.appointmentDate();
            case RESCHEDULED ->
                    "Sua consulta foi remarcada para " + event.appointmentDate();
            case CANCELLED ->
                    "Sua consulta foi cancelada";
            case COMPLETED ->
                    "Sua consulta foi realizada";
        };
    }

    public List<Notification> findByPatientId(Long patientId) {
        return notificationRepository.findByPatientId(patientId);
    }
}
