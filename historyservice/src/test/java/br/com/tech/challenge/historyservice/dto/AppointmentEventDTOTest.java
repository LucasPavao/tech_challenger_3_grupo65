package br.com.tech.challenge.historyservice.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JsonTest
class AppointmentEventDTOTest {

    private static final String PAYLOAD_COMPLETO = """
            {
              "eventId": "8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b",
              "eventStatus": "SCHEDULED",
              "occurredAt": "2026-08-30T14:32:10Z",
              "appointmentId": 42,
              "patientId": 10,
              "patientName": "Maria Souza",
              "doctorId": 7,
              "doctorName": "Dr. Joao Lima",
              "appointmentDate": "2026-09-05T09:00:00",
              "description": "Consulta de rotina"
            }
            """;

    @Autowired
    private JacksonTester<AppointmentEventDTO> json;

    @Test
    void desserializaPayloadCompleto() throws Exception {
        AppointmentEventDTO evento = json.parseObject(PAYLOAD_COMPLETO);

        assertThat(evento.eventId()).isEqualTo(UUID.fromString("8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b"));
        assertThat(evento.eventStatus()).isEqualTo(AppointmentEventStatus.SCHEDULED);
        assertThat(evento.occurredAt()).isEqualTo(Instant.parse("2026-08-30T14:32:10Z"));
        assertThat(evento.appointmentId()).isEqualTo(42L);
        assertThat(evento.patientId()).isEqualTo(10L);
        assertThat(evento.patientName()).isEqualTo("Maria Souza");
        assertThat(evento.doctorId()).isEqualTo(7L);
        assertThat(evento.doctorName()).isEqualTo("Dr. Joao Lima");
        assertThat(evento.appointmentDate()).isEqualTo(LocalDateTime.of(2026, 9, 5, 9, 0));
        assertThat(evento.description()).isEqualTo("Consulta de rotina");
    }

    @Test
    void aceitaNomesEDescricaoAusentes() throws Exception {
        String semNomes = """
                {
                  "eventId": "8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b",
                  "eventStatus": "CANCELLED",
                  "occurredAt": "2026-08-30T14:32:10Z",
                  "appointmentId": 42,
                  "patientId": 10,
                  "doctorId": 7,
                  "appointmentDate": "2026-09-05T09:00:00"
                }
                """;

        AppointmentEventDTO evento = json.parseObject(semNomes);

        assertThat(evento.patientName()).isNull();
        assertThat(evento.doctorName()).isNull();
        assertThat(evento.description()).isNull();
        assertThat(evento.eventStatus()).isEqualTo(AppointmentEventStatus.CANCELLED);
    }

    @Test
    void desserializaTodasAsTransicoes() throws Exception {
        for (AppointmentEventStatus esperado : AppointmentEventStatus.values()) {
            AppointmentEventDTO evento = json.parseObject(
                    PAYLOAD_COMPLETO.replace("\"SCHEDULED\"", "\"" + esperado.name() + "\""));

            assertThat(evento.eventStatus()).isEqualTo(esperado);
        }
    }

    @Test
    void rejeitaCampoDesconhecido() {
        String comCampoExtra = PAYLOAD_COMPLETO.replace(
                "\"description\": \"Consulta de rotina\"",
                "\"description\": \"Consulta de rotina\", \"campoInesperado\": true");

        assertThatThrownBy(() -> json.parseObject(comCampoExtra))
                .hasMessageContaining("campoInesperado");
    }
}
