package br.com.tech.challenge.appointmentservice.dto;

import br.com.tech.challenge.appointmentservice.enums.AppointmentStatus;
import jakarta.validation.constraints.NotNull;

public record AppointmentStatusRequest(
        @NotNull(message = "status é obrigatório")
        AppointmentStatus status
) {
}
