package br.com.tech.challenge.appointmentservice.dto;

import br.com.tech.challenge.appointmentservice.enums.AppointmentStatus;

import java.time.LocalDateTime;

public record AppointmentResponse(
        Long id,
        Long patientId,
        Long doctorId,
        LocalDateTime appointmentDate,
        String description,
        AppointmentStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
