package br.com.tech.challenge.notificationservice.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * PATIENT so ve as proprias notificacoes: o patientId da rota precisa ser o user_id do token.
 * Mesma regra do AppointmentAuthorization (appointment-service) e do MedicalHistoryQueryService
 * (history-service). DOCTOR e NURSE veem qualquer paciente.
 */
@Component
public class NotificationAuthorization {

    public void checkPatientAccess(Authentication authentication, Long patientId) {
        if (authentication instanceof JwtAuthenticationToken jwt
                && jwt.getAuthorities().stream().anyMatch(a -> Objects.equals(a.getAuthority(), "ROLE_PATIENT"))) {
            Number userId = jwt.getToken().getClaim("user_id");
            if (userId == null || userId.longValue() != patientId) {
                throw new AccessDeniedException("Patient cannot access other patient notifications");
            }
        }
    }
}
