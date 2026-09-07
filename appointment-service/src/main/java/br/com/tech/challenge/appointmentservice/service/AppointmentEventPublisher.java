package br.com.tech.challenge.appointmentservice.service;

import br.com.tech.challenge.appointmentservice.config.MessagingProperties;
import br.com.tech.challenge.appointmentservice.entity.Appointment;
import br.com.tech.challenge.appointmentservice.event.AppointmentEvent;
import br.com.tech.challenge.appointmentservice.event.AppointmentEventStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AppointmentEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

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

        rabbitTemplate.convertAndSend(
                properties.historyExchange(),
                properties.historyRoutingKey(),
                event
        );

        if (properties.publishNotification()
                && properties.notificationExchange() != null
                && !properties.notificationExchange().isBlank()) {
            rabbitTemplate.convertAndSend(
                    properties.notificationExchange(),
                    properties.notificationRoutingKey(),
                    event
            );
        }

        return event;
    }
}
