package br.com.tech.challenge.historyservice.services;

import java.util.List;

import br.com.tech.challenge.historyservice.dto.MedicalRecordResponse;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leitura do historico. Separado do HistoryIngestionService de proposito: um so le, o outro so
 * escreve.
 *
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
    public List<MedicalRecordResponse> patientHistory(Long patientId) {
        if (patientId == null) {
            throw new IllegalArgumentException("patientId nao pode ser nulo");
        }

        return repository.findLatestEventPerAppointment(patientId).stream()
                .map(MedicalRecordResponse::from)
                .toList();
    }
}
