package br.com.tech.challenge.historyservice.services;

import java.util.List;
import java.util.Objects;

import br.com.tech.challenge.historyservice.dto.MedicalRecordResponse;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Leitura do historico. Separado do HistoryIngestionService de proposito: um so le, o outro so
 * escreve.
 * <p>
 * E aqui que a autorizacao entra na proxima fase -- a regra "PATIENT so acessa o proprio
 * patientId" depende do valor do argumento, entao nao cabe numa anotacao de resolver.
 */
@Service
@Transactional(readOnly = true)
public class MedicalHistoryQueryService {

    private final MedicalHistoryRepository repository;

    public MedicalHistoryQueryService(MedicalHistoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Estado atual de cada consulta do paciente. Paciente sem historico devolve lista vazia: este
     * servico nao conhece o cadastro de pacientes e nao pode afirmar que o paciente nao existe.
     */
    public List<MedicalRecordResponse> patientHistory(Long patientId, Authentication authentication) {
        if (patientId == null) {
            throw new IllegalArgumentException("patientId cannot be null");
        }

        checkPatientAccess(authentication, patientId);

        return repository.findLatestEventPerAppointment(patientId).stream()
                .map(MedicalRecordResponse::from)
                .toList();
    }

    /**
     * Trilha completa de uma consulta, do evento mais antigo ao mais recente. E o que o log
     * append-only oferece e uma tabela de estado nao: a data anterior de uma consulta remarcada
     * continua visivel na linha anterior.
     */
    public List<MedicalRecordResponse> appointmentTimeline(Long appointmentId) {
        if (appointmentId == null) {
            throw new IllegalArgumentException("appointmentId cannot be null");
        }

        return repository.findByAppointmentIdOrderByOccurredAtAscIdAsc(appointmentId).stream()
                .map(MedicalRecordResponse::from)
                .toList();
    }

    private void checkPatientAccess(Authentication authentication, Long patientId) {
        if (authentication instanceof JwtAuthenticationToken jwt
                && jwt.getAuthorities().stream().anyMatch(a -> Objects.equals(a.getAuthority(), "ROLE_PATIENT"))) {
            Number userId = jwt.getToken().getClaim("user_id");
            if (userId == null || userId.longValue() != patientId) {
                throw new AccessDeniedException("Patient cannot access other patient appointment");
            }
        }
    }
}
