package br.com.tech.challenge.appointmentservice.service;

import br.com.tech.challenge.appointmentservice.dto.AppointmentRequest;
import br.com.tech.challenge.appointmentservice.dto.AppointmentResponse;
import br.com.tech.challenge.appointmentservice.dto.AppointmentStatusRequest;
import br.com.tech.challenge.appointmentservice.entity.Appointment;
import br.com.tech.challenge.appointmentservice.enums.AppointmentStatus;
import br.com.tech.challenge.appointmentservice.event.AppointmentEventStatus;
import br.com.tech.challenge.appointmentservice.exception.AppointmentNotFoundException;
import br.com.tech.challenge.appointmentservice.exception.BusinessException;
import br.com.tech.challenge.appointmentservice.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository repository;
    private final AppointmentEventPublisher eventPublisher;

    @Transactional
    public AppointmentResponse create(AppointmentRequest request) {
        Appointment appointment = Appointment.builder()
                .patientId(request.patientId())
                .doctorId(request.doctorId())
                .appointmentDate(request.appointmentDate())
                .description(request.description().trim())
                .status(AppointmentStatus.SCHEDULED)
                .build();

        Appointment saved = repository.save(appointment);
        eventPublisher.publish(saved, AppointmentEventStatus.SCHEDULED);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public AppointmentResponse findById(Long id) {
        return toResponse(findEntity(id));
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> findAll() {
        return repository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> findByPatient(Long patientId) {
        return repository.findByPatientIdOrderByAppointmentDateAsc(patientId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AppointmentResponse update(Long id, AppointmentRequest request) {
        Appointment appointment = findEntity(id);
        validateCanBeChanged(appointment);

        boolean dateChanged = !appointment.getAppointmentDate().equals(request.appointmentDate());

        appointment.setPatientId(request.patientId());
        appointment.setDoctorId(request.doctorId());
        appointment.setAppointmentDate(request.appointmentDate());
        appointment.setDescription(request.description().trim());

        Appointment updated = repository.save(appointment);
        eventPublisher.publish(
                updated,
                dateChanged ? AppointmentEventStatus.RESCHEDULED : AppointmentEventStatus.SCHEDULED
        );

        return toResponse(updated);
    }

    @Transactional
    public AppointmentResponse updateStatus(Long id, AppointmentStatusRequest request) {
        Appointment appointment = findEntity(id);
        AppointmentStatus newStatus = request.status();

        validateStatusTransition(appointment.getStatus(), newStatus);
        appointment.setStatus(newStatus);

        Appointment updated = repository.save(appointment);
        eventPublisher.publish(updated, toEventStatus(newStatus));

        return toResponse(updated);
    }

    private Appointment findEntity(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new AppointmentNotFoundException(id));
    }

    private void validateCanBeChanged(Appointment appointment) {
        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new BusinessException("Consulta concluída não pode ser alterada");
        }
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new BusinessException("Consulta cancelada não pode ser alterada");
        }
    }

    private void validateStatusTransition(AppointmentStatus current, AppointmentStatus next) {
        if (current == next) {
            throw new BusinessException("A consulta já possui este status");
        }
        if (current == AppointmentStatus.COMPLETED) {
            throw new BusinessException("Consulta concluída não pode mudar de status");
        }
        if (current == AppointmentStatus.CANCELLED) {
            throw new BusinessException("Consulta cancelada não pode mudar de status");
        }
        if (current == AppointmentStatus.SCHEDULED
                && next != AppointmentStatus.COMPLETED
                && next != AppointmentStatus.CANCELLED) {
            throw new BusinessException("Transição de status inválida");
        }
    }

    private AppointmentEventStatus toEventStatus(AppointmentStatus status) {
        return switch (status) {
            case SCHEDULED -> AppointmentEventStatus.SCHEDULED;
            case COMPLETED -> AppointmentEventStatus.COMPLETED;
            case CANCELLED -> AppointmentEventStatus.CANCELLED;
        };
    }

    private AppointmentResponse toResponse(Appointment appointment) {
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getPatientId(),
                appointment.getDoctorId(),
                appointment.getAppointmentDate(),
                appointment.getDescription(),
                appointment.getStatus(),
                appointment.getCreatedAt(),
                appointment.getUpdatedAt()
        );
    }
}
