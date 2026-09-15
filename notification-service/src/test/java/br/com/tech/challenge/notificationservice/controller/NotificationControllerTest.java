package br.com.tech.challenge.notificationservice.controller;

import br.com.tech.challenge.notificationservice.config.NotificationAuthorization;
import br.com.tech.challenge.notificationservice.config.SecurityConfig;
import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.model.NotificationStatus;
import br.com.tech.challenge.notificationservice.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fatia web com a SecurityConfig real. O JwtDecoder e mockado: cada "token" da requisicao vira um
 * Jwt com a role e o user_id do cenario, sem precisar de assinatura.
 */
@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, NotificationAuthorization.class})
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode("nurse-token")).thenReturn(jwt("ROLE_NURSE", 3L));
        when(jwtDecoder.decode("patient-10-token")).thenReturn(jwt("ROLE_PATIENT", 10L));
    }

    @Test
    void semTokenDevolve401() throws Exception {
        mockMvc.perform(get("/notifications/patient/10"))
                .andExpect(status().isUnauthorized());

        verify(notificationService, never()).findByPatientId(any());
    }

    @Test
    void enfermeiraListaAsNotificacoesDoPaciente() throws Exception {
        when(notificationService.findByPatientId(10L)).thenReturn(List.of(notificacao()));

        mockMvc.perform(get("/notifications/patient/10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer nurse-token"))
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

        mockMvc.perform(get("/notifications/patient/99")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer nurse-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void pacienteListaAsPropriasNotificacoes() throws Exception {
        when(notificationService.findByPatientId(10L)).thenReturn(List.of(notificacao()));

        mockMvc.perform(get("/notifications/patient/10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer patient-10-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void pacienteNaoListaNotificacoesDeOutroPaciente() throws Exception {
        mockMvc.perform(get("/notifications/patient/11")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer patient-10-token"))
                .andExpect(status().isForbidden());

        verify(notificationService, never()).findByPatientId(any());
    }

    private Notification notificacao() {
        Notification notification = new Notification();
        notification.setId(1L);
        notification.setEventId(UUID.fromString("8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b"));
        notification.setEventStatus(AppointmentEventStatus.SCHEDULED);
        notification.setAppointmentId(42L);
        notification.setPatientId(10L);
        notification.setMessage("Sua consulta foi agendada para 2030-09-10T14:30");
        notification.setCreatedAt(LocalDateTime.of(2026, 9, 12, 14, 0));
        notification.setStatus(NotificationStatus.SENT);
        return notification;
    }

    private Jwt jwt(String scope, long userId) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("scope", scope)
                .claim("user_id", userId)
                .subject("user@hospital.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
