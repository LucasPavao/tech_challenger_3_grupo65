package br.com.tech.challenge.historyservice.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Contrato do evento publicado pelo appointment-service.
 * Formato documentado em docs/messaging/appointment-event.md.
 * patientName, doctorName e description sao opcionais; o restante e obrigatorio.
 */
public record AppointmentEventDTO(
        @NotNull UUID eventId,
        @NotNull AppointmentEventStatus eventStatus,
        @NotNull Instant occurredAt,
        @NotNull Long appointmentId,
        @NotNull Long patientId,
        String patientName,
        @NotNull Long doctorId,
        String doctorName,
        @NotNull LocalDateTime appointmentDate,
        String description) {
}
