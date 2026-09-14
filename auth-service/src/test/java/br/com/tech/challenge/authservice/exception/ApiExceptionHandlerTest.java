package br.com.tech.challenge.authservice.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void shouldReturnEmailAlreadyExistsWhenCauseMentionsEmail() {
        Throwable cause = new RuntimeException(
                "duplicate key value violates unique constraint \"users_email_key\" (email)=(patient@hospital.com)");
        DataIntegrityViolationException exception = new DataIntegrityViolationException("integrity violation", cause);

        ResponseEntity<Map<String, String>> response = handler.dataIntegrityViolation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "Email already exists");
    }

    @Test
    void shouldReturnGenericMessageWhenCauseDoesNotMentionEmail() {
        Throwable cause = new RuntimeException(
                "null value in column \"name\" violates not-null constraint");
        DataIntegrityViolationException exception = new DataIntegrityViolationException("integrity violation", cause);

        ResponseEntity<Map<String, String>> response = handler.dataIntegrityViolation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "Request violates a data integrity constraint");
    }
}
