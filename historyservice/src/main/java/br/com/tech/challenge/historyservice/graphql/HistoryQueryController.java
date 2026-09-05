package br.com.tech.challenge.historyservice.graphql;

import java.util.List;

import br.com.tech.challenge.historyservice.dto.MedicalRecordResponse;
import br.com.tech.challenge.historyservice.services.MedicalHistoryQueryService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * Resolvers das queries do historico. Cada @QueryMapping casa por nome com um campo do type Query
 * em graphql/schema.graphqls.
 *
 * O controller so traduz argumentos e resposta; a regra de leitura fica no service.
 */
@Controller
public class HistoryQueryController {

    private final MedicalHistoryQueryService queryService;

    public HistoryQueryController(MedicalHistoryQueryService queryService) {
        this.queryService = queryService;
    }

    @QueryMapping
    public List<MedicalRecordResponse> patientHistory(@Argument Long patientId) {
        return queryService.patientHistory(patientId);
    }

    @QueryMapping
    public List<MedicalRecordResponse> appointmentTimeline(@Argument Long appointmentId) {
        return queryService.appointmentTimeline(appointmentId);
    }
}
