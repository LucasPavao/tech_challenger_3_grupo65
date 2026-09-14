package br.com.tech.challenge.historyservice.graphql;

import java.util.List;

import br.com.tech.challenge.historyservice.dto.MedicalRecordResponse;
import br.com.tech.challenge.historyservice.services.MedicalHistoryQueryService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

/**
 * Resolvers das queries do historico. Cada @QueryMapping casa por nome com um campo do type Query
 * em graphql/schema.graphqls.
 * <p>
 * O controller so traduz argumentos e resposta; a regra de leitura fica no service.
 */
@Controller
public class HistoryQueryController {

    private final MedicalHistoryQueryService queryService;

    public HistoryQueryController(MedicalHistoryQueryService queryService) {
        this.queryService = queryService;
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'PATIENT')")
    public List<MedicalRecordResponse> patientHistory(@Argument Long patientId, Authentication authentication) {
        return queryService.patientHistory(patientId, authentication);
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE')")
    public List<MedicalRecordResponse> appointmentTimeline(@Argument Long appointmentId) {
        return queryService.appointmentTimeline(appointmentId);
    }
}
