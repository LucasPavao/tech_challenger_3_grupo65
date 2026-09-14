package br.com.tech.challenge.appointmentservice.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class AppointmentAuthorization {

    public void checkPatientAccess(Authentication authentication, Long patientId) {
        if (authentication instanceof JwtAuthenticationToken jwt
                && jwt.getAuthorities().stream().anyMatch(a -> Objects.equals(a.getAuthority(), "ROLE_PATIENT"))) {
            Number userId = jwt.getToken().getClaim("user_id");
            if (userId == null || userId.longValue() != patientId) {
                throw new AccessDeniedException("Patient cannot access other patient appointment");
            }
        }
    }
}
