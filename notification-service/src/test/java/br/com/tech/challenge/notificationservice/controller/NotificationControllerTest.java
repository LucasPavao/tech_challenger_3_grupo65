package br.com.tech.challenge.notificationservice.controller;

import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    void listaAsNotificacoesDoPaciente() throws Exception {
        Notification notification = new Notification();
        notification.setId(1L);
        notification.setEventId(UUID.fromString("8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b"));
        notification.setEventStatus(AppointmentEventStatus.SCHEDULED);
        notification.setAppointmentId(42L);
        notification.setPatientId(10L);
        notification.setMessage("Sua consulta foi agendada para 2030-09-10T14:30");
        notification.setCreatedAt(LocalDateTime.of(2026, 9, 12, 14, 0));
        notification.setStatus(NotificationStatus.SENT);
        when(notificationService.findByPatientId(10L)).thenReturn(List.of(notification));

        mockMvc.perform(get("/notifications/patient/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].patientId").value(10))
                .andExpect(jsonPath("$[0].appointmentId").value(42))
                .andExpect(jsonPath("$[0].eventStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$[0].status").value("SENT"));
    }

    @Test
    void devolveListaVaziaParaPacienteSemNotificacoes() throws Exception {
        when(notificationService.findByPatientId(99L)).thenReturn(List.of());

        mockMvc.perform(get("/notifications/patient/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
