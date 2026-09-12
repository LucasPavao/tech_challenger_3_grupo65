package br.com.tech.challenge.authservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<Map<String, String>> userNotFound(UserNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(RoleNotFoundException.class)
    ResponseEntity<Map<String, String>> roleNotFound(RoleNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    ResponseEntity<Map<String, String>> emailAlreadyExists(EmailAlreadyExistsException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(errors);
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
