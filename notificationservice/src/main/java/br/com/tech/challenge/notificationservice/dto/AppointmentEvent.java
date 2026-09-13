package br.com.tech.challenge.notificationservice.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Contrato do evento publicado pelo appointment-service.
 * Formato documentado em docs/messaging/appointment-event.md.
 * 
 * patientName e doctorName são opcionais; o restante é obrigatório.
 */
public record AppointmentEvent(
        @NotNull UUID eventId,
        @NotNull AppointmentEventStatus eventStatus,
        @NotNull Instant occurredAt,
        @NotNull Long appointmentId,
        @NotNull Long patientId,
        String patientName,
        @NotNull Long doctorId,
        String doctorName,
        @NotNull LocalDateTime appointmentDate,
        String description
) {
}
