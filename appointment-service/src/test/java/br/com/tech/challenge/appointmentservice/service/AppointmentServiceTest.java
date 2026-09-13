package br.com.tech.challenge.appointmentservice.service;

import br.com.tech.challenge.appointmentservice.dto.AppointmentRequest;
import br.com.tech.challenge.appointmentservice.dto.AppointmentStatusRequest;
import br.com.tech.challenge.appointmentservice.entity.Appointment;
import br.com.tech.challenge.appointmentservice.enums.AppointmentStatus;
import br.com.tech.challenge.appointmentservice.event.AppointmentEventStatus;
import br.com.tech.challenge.appointmentservice.exception.AppointmentNotFoundException;
import br.com.tech.challenge.appointmentservice.exception.BusinessException;
import br.com.tech.challenge.appointmentservice.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock
    private AppointmentRepository repository;

    @Mock
    private AppointmentEventPublisher eventPublisher;

    private AppointmentService service;

    @BeforeEach
    void setUp() {
        service = new AppointmentService(repository, eventPublisher);
    }

    @Test
    void shouldCreateAppointmentAndPublishScheduledEvent() {
        AppointmentRequest request = request(10L, 7L, "Consulta cardiológica");
        Appointment saved = appointment(42L, 10L, 7L, request.appointmentDate(), AppointmentStatus.SCHEDULED);

        when(repository.save(any(Appointment.class))).thenReturn(saved);

        var response = service.create(request);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.status()).isEqualTo(AppointmentStatus.SCHEDULED);
        verify(eventPublisher).publish(saved, AppointmentEventStatus.SCHEDULED);
    }

    @Test
    void shouldFindAppointment() {
        Appointment appointment = appointment(42L, 10L, 7L,
                LocalDateTime.now().plusDays(2), AppointmentStatus.SCHEDULED);
        when(repository.findById(42L)).thenReturn(Optional.of(appointment));

        var response = service.findById(42L);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.patientId()).isEqualTo(10L);
    }

    @Test
    void shouldThrowWhenAppointmentDoesNotExist() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(AppointmentNotFoundException.class);
    }

    @Test
    void shouldListAppointmentsByPatient() {
        Appointment a1 = appointment(1L, 10L, 7L, LocalDateTime.now().plusDays(1), AppointmentStatus.SCHEDULED);
        Appointment a2 = appointment(2L, 10L, 8L, LocalDateTime.now().plusDays(3), AppointmentStatus.SCHEDULED);
        when(repository.findByPatientIdOrderByAppointmentDateAsc(10L)).thenReturn(List.of(a1, a2));

        var result = service.findByPatient(10L);

        assertThat(result).hasSize(2);
        assertThat(result).extracting("patientId").containsOnly(10L);
    }

    @Test
    void shouldPublishRescheduledWhenDateChanges() {
        LocalDateTime oldDate = LocalDateTime.now().plusDays(2);
        LocalDateTime newDate = LocalDateTime.now().plusDays(5);
        Appointment appointment = appointment(42L, 10L, 7L, oldDate, AppointmentStatus.SCHEDULED);
        AppointmentRequest request = request(10L, 7L, newDate, "Consulta remarcada");

        when(repository.findById(42L)).thenReturn(Optional.of(appointment));
        when(repository.save(appointment)).thenReturn(appointment);

        service.update(42L, request);

        verify(eventPublisher).publish(appointment, AppointmentEventStatus.RESCHEDULED);
    }

    @Test
    void shouldPublishScheduledWhenEditingWithoutChangingDate() {
        LocalDateTime date = LocalDateTime.now().plusDays(2);
        Appointment appointment = appointment(42L, 10L, 7L, date, AppointmentStatus.SCHEDULED);
        AppointmentRequest request = request(10L, 7L, date, "Descrição atualizada");

        when(repository.findById(42L)).thenReturn(Optional.of(appointment));
        when(repository.save(appointment)).thenReturn(appointment);

        service.update(42L, request);

        verify(eventPublisher).publish(appointment, AppointmentEventStatus.SCHEDULED);
    }

    @Test
    void shouldNotEditCompletedAppointment() {
        Appointment appointment = appointment(42L, 10L, 7L,
                LocalDateTime.now().plusDays(2), AppointmentStatus.COMPLETED);
        when(repository.findById(42L)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> service.update(42L, request(10L, 7L, "Tentativa")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("concluída");

        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void shouldCancelScheduledAppointment() {
        Appointment appointment = appointment(42L, 10L, 7L,
                LocalDateTime.now().plusDays(2), AppointmentStatus.SCHEDULED);
        when(repository.findById(42L)).thenReturn(Optional.of(appointment));
        when(repository.save(appointment)).thenReturn(appointment);

        service.updateStatus(42L, new AppointmentStatusRequest(AppointmentStatus.CANCELLED));

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(eventPublisher).publish(appointment, AppointmentEventStatus.CANCELLED);
    }

    @Test
    void shouldCompleteScheduledAppointment() {
        Appointment appointment = appointment(42L, 10L, 7L,
                LocalDateTime.now().plusDays(2), AppointmentStatus.SCHEDULED);
        when(repository.findById(42L)).thenReturn(Optional.of(appointment));
        when(repository.save(appointment)).thenReturn(appointment);

        service.updateStatus(42L, new AppointmentStatusRequest(AppointmentStatus.COMPLETED));

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        verify(eventPublisher).publish(appointment, AppointmentEventStatus.COMPLETED);
    }

    @Test
    void shouldRejectRepeatedStatus() {
        Appointment appointment = appointment(42L, 10L, 7L,
                LocalDateTime.now().plusDays(2), AppointmentStatus.SCHEDULED);
        when(repository.findById(42L)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> service.updateStatus(42L,
                new AppointmentStatusRequest(AppointmentStatus.SCHEDULED)))
                .isInstanceOf(BusinessException.class);

        verify(repository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void shouldNotChangeCancelledAppointment() {
        Appointment appointment = appointment(42L, 10L, 7L,
                LocalDateTime.now().plusDays(2), AppointmentStatus.CANCELLED);
        when(repository.findById(42L)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> service.updateStatus(42L,
                new AppointmentStatusRequest(AppointmentStatus.COMPLETED)))
                .isInstanceOf(BusinessException.class);

        verify(repository, never()).save(any());
    }

    private AppointmentRequest request(Long patientId, Long doctorId, String description) {
        return request(patientId, doctorId, LocalDateTime.now().plusDays(3), description);
    }

    private AppointmentRequest request(Long patientId, Long doctorId,
                                       LocalDateTime date, String description) {
        return new AppointmentRequest(patientId, doctorId, date, description);
    }

    private Appointment appointment(Long id, Long patientId, Long doctorId,
                                    LocalDateTime date, AppointmentStatus status) {
        return Appointment.builder()
                .id(id)
                .patientId(patientId)
                .doctorId(doctorId)
                .appointmentDate(date)
                .description("Consulta")
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
