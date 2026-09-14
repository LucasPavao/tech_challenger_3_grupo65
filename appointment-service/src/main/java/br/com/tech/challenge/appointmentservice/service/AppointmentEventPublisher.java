package br.com.tech.challenge.appointmentservice.service;

import br.com.tech.challenge.appointmentservice.config.MessagingProperties;
import br.com.tech.challenge.appointmentservice.entity.Appointment;
import br.com.tech.challenge.appointmentservice.event.AppointmentEvent;
import br.com.tech.challenge.appointmentservice.event.AppointmentEventStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AppointmentEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    /**
     * Publica evento de consulta para os consumidores (history-service e notification-service)
     * usando o mesmo exchange com routing keys diferentes.
     *
     * @param appointment Consulta que sofreu alteração
     * @param status Status da consulta após a alteração
     * @return Evento publicado
     */
    public AppointmentEvent publish(Appointment appointment, AppointmentEventStatus status) {
        AppointmentEvent event = new AppointmentEvent(
                UUID.randomUUID(),
                status,
                Instant.now(),
                appointment.getId(),
                appointment.getPatientId(),
                null,
                appointment.getDoctorId(),
                null,
                appointment.getAppointmentDate(),
                appointment.getDescription()
        );

        log.info("Publicando evento de appointment: appointmentId={}, eventStatus={}, eventId={}",
                appointment.getId(), status, event.eventId());

        // Publica para history-service com routing key history.created
        rabbitTemplate.convertAndSend(
                properties.appointmentExchange(),
                properties.historyRoutingKey(),
                event
        );

        // Publica para notification-service com routing key notification.created
        rabbitTemplate.convertAndSend(
                properties.appointmentExchange(),
                properties.notificationRoutingKey(),
                event
        );

        log.debug("Evento publicado com sucesso: eventId={}", event.eventId());
        return event;
    }
}
