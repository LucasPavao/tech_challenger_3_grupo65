package br.com.tech.challenge.notificationservice.service;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.notification.NotificationSender;
import br.com.tech.challenge.notificationservice.repository.NotificationRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationSender notificationSender) {

        this.notificationRepository = notificationRepository;
        this.notificationSender = notificationSender;
    }

    public Notification processAppointmentEvent(
            AppointmentEvent event) {

        Notification notification =
                createNotification(event);

        Notification saved =
                notificationRepository.save(notification);

        notificationSender.send(saved);

        saved.setStatus(NotificationStatus.SENT);

        return notificationRepository.save(saved);
    }

    private Notification createNotification(
            AppointmentEvent event) {

        Notification notification = new Notification();

        notification.setAppointmentId(
                event.getAppointmentId());

        notification.setPatientId(
                event.getPatientId());

        notification.setMessage(
                createMessage(event));

        notification.setCreatedAt(
                LocalDateTime.now());

        notification.setStatus(
                NotificationStatus.PENDING);

        return notification;
    }

    private String createMessage(
            AppointmentEvent event) {

        return switch (event.getEventType()) {

            case CREATED ->
                    "Sua consulta foi agendada para "
                            + event.getDateTime();

            case UPDATED ->
                    "Sua consulta foi atualizada para "
                            + event.getDateTime();
        };
    }

    public List<Notification> findByPatientId(Long patientId) {
        return notificationRepository.findByPatientId(patientId);
    }
}
