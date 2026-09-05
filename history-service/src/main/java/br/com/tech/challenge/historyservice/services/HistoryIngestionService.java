package br.com.tech.challenge.historyservice.services;

import java.util.Set;

import br.com.tech.challenge.historyservice.dto.AppointmentEventDTO;
import br.com.tech.challenge.historyservice.entities.MedicalHistory;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Grava cada AppointmentEvent recebido como uma linha nova em medical_history.
 * Nao atualiza registros existentes: o historico e append-only.
 */
@Slf4j
@Service
public class HistoryIngestionService {

    private final MedicalHistoryRepository repository;
    private final Validator validator;

    public HistoryIngestionService(MedicalHistoryRepository repository, Validator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    /**
     * Sem @Transactional de proposito. Com uma transacao no metodo, a violacao do UNIQUE(event_id)
     * marca a transacao como rollback-only e o catch abaixo nao a limpa: o interceptor do Spring
     * lanca UnexpectedRollbackException no commit, ja fora do catch, e o listener manda para a DLQ
     * um evento que ja esta gravado. Sem ela, o save roda na transacao do proprio repositorio e a
     * DataIntegrityViolationException chega ao catch com o rollback ja resolvido.
     *
     * Nao ha o que atomizar aqui: a ingestao grava uma unica linha.
     */
    public void ingest(AppointmentEventDTO evento) {
        if (evento == null) {
            throw new IllegalArgumentException("AppointmentEvent nao pode ser nulo");
        }

        Set<ConstraintViolation<AppointmentEventDTO>> violacoes = validator.validate(evento);
        if (!violacoes.isEmpty()) {
            throw new ConstraintViolationException(violacoes);
        }

        if (repository.existsByEventId(evento.eventId())) {
            log.warn("Evento {} ja processado, ignorando reentrega", evento.eventId());
            return;
        }

        try {
            repository.save(toEntity(evento));
            log.debug("Historico gravado para appointment {} a partir do evento {}",
                    evento.appointmentId(), evento.eventId());
        } catch (DataIntegrityViolationException e) {
            // Corrida entre consumers processando a mesma reentrega: o UNIQUE(event_id) barrou .
            log.warn("Evento {} inserido concorrentemente, ignorando", evento.eventId());
        }
    }

    private MedicalHistory toEntity(AppointmentEventDTO evento) {
        return MedicalHistory.builder()
                .eventId(evento.eventId())
                .eventStatus(evento.eventStatus())
                .occurredAt(evento.occurredAt())
                .appointmentId(evento.appointmentId())
                .patientId(evento.patientId())
                .patientName(evento.patientName())
                .doctorId(evento.doctorId())
                .doctorName(evento.doctorName())
                .appointmentDate(evento.appointmentDate())
                .description(evento.description())
                .build();
    }
}
