package br.com.tech.challenge.historyservice.repositories;

import java.util.List;
import java.util.UUID;

import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MedicalHistoryRepository extends JpaRepository<MedicalHistory, Long> {

    boolean existsByEventId(UUID eventId);

    List<MedicalHistory> findByAppointmentIdOrderByOccurredAtAsc(Long appointmentId);

    /**
     * Estado atual de cada consulta do paciente: colapsa a trilha append-only para o evento mais
     * recente de cada appointment_id.
     *
     * DISTINCT ON e uma extensao do Postgres -- devolve a primeira linha de cada grupo, por isso o
     * ORDER BY interno precisa comecar por appointment_id. A subquery reordena o resultado por
     * data do evento, que e a ordem que o cliente ve.
     */
    @Query(value = """
            SELECT * FROM (
                SELECT DISTINCT ON (appointment_id) *
                FROM medical_history
                WHERE patient_id = :patientId
                ORDER BY appointment_id, occurred_at DESC
            ) AS ultimo
            ORDER BY ultimo.occurred_at DESC
            """, nativeQuery = true)
    List<MedicalHistory> findLatestEventPerAppointment(@Param("patientId") Long patientId);
}
