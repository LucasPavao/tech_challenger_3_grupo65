package br.com.tech.challenge.appointmentservice.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;


public record AppointmentEvent(
        UUID eventId,
        AppointmentEventStatus eventStatus,
        Instant occurredAt,
        Long appointmentId,
        Long patientId,
        String patientName,
        Long doctorId,
        String doctorName,
        LocalDateTime appointmentDate,
        String description
) {
}
