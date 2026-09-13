package br.com.tech.challenge.appointmentservice.exception;

public class AppointmentNotFoundException extends RuntimeException {
    public AppointmentNotFoundException(Long id) {
        super("Consulta não encontrada: " + id);
    }
}
