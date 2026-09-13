package br.com.tech.challenge.notificationservice.service;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.notification.NotificationSender;
import br.com.tech.challenge.notificationservice.repository.NotificationRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;
    private final Validator validator;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationSender notificationSender,
            Validator validator) {

        this.notificationRepository = notificationRepository;
        this.notificationSender = notificationSender;
        this.validator = validator;
    }

    /**
     * Processa um AppointmentEvent e devolve a unica notificacao associada ao seu eventId.
     *
     * Idempotente pelo eventId: uma reentrega do mesmo evento nao grava uma segunda linha. Se a
     * notificacao ja foi enviada, e devolvida sem reenvio; se ficou PENDING por uma falha anterior,
     * o envio e tentado de novo sobre a mesma linha.
     *
     * Sem @Transactional de proposito, pelo mesmo motivo do HistoryIngestionService: com uma
     * transacao no metodo, a violacao do UNIQUE(event_id) marcaria a transacao como rollback-only e
     * o commit lancaria UnexpectedRollbackException fora do catch, mandando para a DLQ um evento ja
     * gravado. Cada save roda na transacao do proprio repositorio.
     *
     * @throws IllegalArgumentException     se o evento for nulo
     * @throws ConstraintViolationException se o evento violar o contrato; a mensagem vai para a DLQ
     * @throws RuntimeException             se o envio falhar; a notificacao fica PENDING e a mensagem vai para a DLQ
     */
    public Notification processAppointmentEvent(AppointmentEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("AppointmentEvent nao pode ser nulo");
        }
        Set<ConstraintViolation<AppointmentEvent>> violacoes = validator.validate(event);
        if (!violacoes.isEmpty()) {
            throw new ConstraintViolationException(violacoes);
        }

        Optional<Notification> existente = notificationRepository.findByEventId(event.eventId());
        if (existente.isPresent()) {
            Notification notification = existente.get();
            if (notification.getStatus() == NotificationStatus.SENT) {
                log.warn("Evento {} ja notificado, ignorando reentrega", event.eventId());
                return notification;
            }
            log.warn("Evento {} com notificacao PENDING, tentando enviar de novo", event.eventId());
            return send(notification);
        }

        Notification saved;
        try {
            saved = notificationRepository.save(createNotification(event));
        } catch (DataIntegrityViolationException e) {
            // Corrida entre consumers processando a mesma reentrega: o UNIQUE(event_id) barrou.
            log.warn("Evento {} inserido concorrentemente, ignorando", event.eventId());
            return notificationRepository.findByEventId(event.eventId())
                    .orElseThrow(() -> e);
        }
        log.debug("Notificacao criada: id={}, appointmentId={}", saved.getId(), saved.getAppointmentId());

        return send(saved);
    }

    private Notification send(Notification notification) {
        try {
            notificationSender.send(notification);
        } catch (Exception e) {
            log.error("Erro ao enviar notificacao: id={}, appointmentId={}. Erro: {}",
                    notification.getId(), notification.getAppointmentId(), e.getMessage());
            // Mantem o status PENDING: um reprocessamento da DLQ tenta de novo sobre a mesma linha
            throw new RuntimeException("Falha ao enviar notificação", e);
        }
        notification.setStatus(NotificationStatus.SENT);
        log.info("Notificacao enviada: id={}, appointmentId={}", notification.getId(), notification.getAppointmentId());

        return notificationRepository.save(notification);
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
