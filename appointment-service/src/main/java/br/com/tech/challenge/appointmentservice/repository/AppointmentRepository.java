package br.com.tech.challenge.appointmentservice.repository;

import br.com.tech.challenge.appointmentservice.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    List<Appointment> findByPatientIdOrderByAppointmentDateAsc(Long patientId);
}
