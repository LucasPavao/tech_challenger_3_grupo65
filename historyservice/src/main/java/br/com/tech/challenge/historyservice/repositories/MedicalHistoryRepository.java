package br.com.tech.challenge.historyservice.repositories;

import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MedicalHistoryRepository extends JpaRepository<MedicalHistory, Long> {

    boolean existsByEventId(UUID eventId);

    List<MedicalHistory> findByAppointmentIdOrderByOccurredAtAsc(Long appointmentId);
}
