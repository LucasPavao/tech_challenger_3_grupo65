package br.com.tech.challenge.appointmentservice.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record AppointmentRequest(
        @NotNull(message = "patientId é obrigatório")
        Long patientId,

        @NotNull(message = "doctorId é obrigatório")
        Long doctorId,

        @NotNull(message = "appointmentDate é obrigatório")
        @Future(message = "appointmentDate deve estar no futuro")
        LocalDateTime appointmentDate,

        @NotBlank(message = "description é obrigatória")
        @Size(max = 500, message = "description deve ter no máximo 500 caracteres")
        String description
) {
}
