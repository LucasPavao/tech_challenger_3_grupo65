package br.com.tech.challenge.notificationservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Contrato do evento publicado pelo appointment-service.
 * O formato precisa ser IDÊNTICO ao publicado lá (mesmos nomes de campo),
 * já que a (de)serialização é feita via JSON pelo JacksonJsonMessageConverter.
 */
@Data
@NoArgsConstructor
public class AppointmentEvent {

    private Long appointmentId;

    private Long patientId;

    private Long doctorId;

    private LocalDateTime dateTime;

    private String description;

    private EventType eventType;
}
