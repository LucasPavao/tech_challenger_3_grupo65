package br.com.tech.challenge.appointmentservice.service;

import br.com.tech.challenge.appointmentservice.event.AppointmentEvent;
import br.com.tech.challenge.appointmentservice.event.AppointmentEventStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AppointmentEventContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldSerializeAppointmentEventWithExpectedFields() throws Exception {
        AppointmentEvent event = new AppointmentEvent(
                UUID.randomUUID(),
                AppointmentEventStatus.SCHEDULED,
                Instant.parse("2026-09-07T16:00:00Z"),
                42L,
                10L,
                null,
                7L,
                null,
                LocalDateTime.of(2026, 10, 10, 9, 0),
                "Consulta"
        );

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("eventId");
        assertThat(json).contains("eventStatus");
        assertThat(json).contains("occurredAt");
        assertThat(json).contains("appointmentId");
        assertThat(json).contains("patientId");
        assertThat(json).contains("doctorId");
        assertThat(json).contains("appointmentDate");
        assertThat(json).contains("description");
    }
}
