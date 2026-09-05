package br.com.tech.challenge.historyservice.graphql;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import br.com.tech.challenge.historyservice.services.MedicalHistoryQueryService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Valida que o schema carrega e declara os campos esperados. Nao executa query --
 * os resolvers entram nas Tasks 3 e 4.
 */
@GraphQlTest
class GraphQlSchemaTest {

    /**
     * Nao existe bean GraphQLSchema no contexto -- o GraphQlAutoConfiguration expoe GraphQlSource,
     * e o schema sai de GraphQlSource.schema().
     */
    @Autowired
    private GraphQlSource graphQlSource;

    /**
     * O HistoryQueryController e carregado pelo @GraphQlTest, mas @Service nao entra nessa slice.
     * O mock existe so para o controller poder ser construido -- este teste valida o schema,
     * nao o comportamento das queries.
     */
    @MockitoBean
    private MedicalHistoryQueryService queryService;

    private GraphQLSchema schema() {
        return graphQlSource.schema();
    }

    @Test
    void schemaCarregaComOTipoQuery() {
        assertThat(schema().getQueryType()).isNotNull();
        assertThat(schema().getQueryType().getName()).isEqualTo("Query");
    }

    @Test
    void declaraOTipoMedicalRecordComOsCamposDoHistorico() {
        assertThat(schema().getObjectType("MedicalRecord")).isNotNull();
        assertThat(schema().getObjectType("MedicalRecord").getFieldDefinitions())
                .extracting(f -> f.getName())
                .contains("appointmentId", "patientId", "patientName", "doctorId", "doctorName",
                        "description", "appointmentDate", "eventStatus", "occurredAt");
    }

    @Test
    void declaraOEnumComAsQuatroTransicoes() {
        assertThat(schema().getType("AppointmentEventStatus")).isNotNull();
        assertThat(((GraphQLEnumType) schema().getType("AppointmentEventStatus")).getValues())
                .extracting(v -> v.getName())
                .containsExactlyInAnyOrder("SCHEDULED", "RESCHEDULED", "CANCELLED", "COMPLETED");
    }
}
