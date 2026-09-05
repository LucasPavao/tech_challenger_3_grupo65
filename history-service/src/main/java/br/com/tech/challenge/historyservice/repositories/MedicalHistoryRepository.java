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

    /**
     * Trilha da consulta em ordem de ocorrencia. O id (BIGSERIAL) desempata occurred_at iguais na
     * ordem de ingestao, para a trilha nao sair embaralhada.
     */
    List<MedicalHistory> findByAppointmentIdOrderByOccurredAtAscIdAsc(Long appointmentId);

    /**
     * Estado atual de cada consulta do paciente: colapsa a trilha append-only para o evento mais
     * recente de cada appointment_id.
     *
     * DISTINCT ON e uma extensao do Postgres -- devolve a primeira linha de cada grupo, por isso o
     * ORDER BY interno precisa comecar por appointment_id. A subquery reordena o resultado por
     * data do evento, que e a ordem que o cliente ve.
     *
     * O id (BIGSERIAL) desempata occurred_at: o relogio vem do produtor e dois eventos da mesma
     * consulta podem chegar com o mesmo instante. Sem o desempate o Postgres pode escolher qualquer
     * uma das linhas empatadas como estado atual, e a escolha muda com o plano de execucao.
     */
    @Query(value = """
            SELECT * FROM (
                SELECT DISTINCT ON (appointment_id) *
                FROM medical_history
                WHERE patient_id = :patientId
                ORDER BY appointment_id, occurred_at DESC, id DESC
            ) AS ultimo
            ORDER BY ultimo.occurred_at DESC, ultimo.id DESC
            """, nativeQuery = true)
    List<MedicalHistory> findLatestEventPerAppointment(@Param("patientId") Long patientId);
}
