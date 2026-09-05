package br.com.tech.challenge.historyservice.graphql;

import br.com.tech.challenge.historyservice.services.MedicalHistoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@GraphQlTest(HistoryQueryController.class)
@Import(GraphQlExceptionResolver.class)
class GraphQlExceptionResolverTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private MedicalHistoryQueryService queryService;

    @Test
    void traduzArgumentoInvalidoParaBadRequest() {
        when(queryService.patientHistory(any()))
                .thenThrow(new IllegalArgumentException("patientId nao pode ser nulo"));

        graphQlTester.document("{ patientHistory(patientId: 10) { appointmentId } }")
                .execute()
                .errors()
                .satisfy(erros -> {
                    assertThat(erros).hasSize(1);
                    assertThat(erros.getFirst().getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                    assertThat(erros.getFirst().getMessage()).isEqualTo("patientId nao pode ser nulo");
                });
    }

    @Test
    void traduzIdNaoNumericoParaBadRequest() {
        // ID! aceita qualquer string: "abc" passa no schema e falha no bind para Long.
        graphQlTester.document("{ patientHistory(patientId: \"abc\") { appointmentId } }")
                .execute()
                .errors()
                .satisfy(erros -> {
                    assertThat(erros).hasSize(1);
                    assertThat(erros.getFirst().getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                    assertThat(erros.getFirst().getMessage()).contains("patientHistory");
                });
    }

    @Test
    void naoVazaDetalheDeErroInesperado() {
        when(queryService.patientHistory(any()))
                .thenThrow(new IllegalStateException("connection pool exhausted at 10.0.0.5:5432"));

        graphQlTester.document("{ patientHistory(patientId: 10) { appointmentId } }")
                .execute()
                .errors()
                .satisfy(erros -> {
                    assertThat(erros).hasSize(1);
                    assertThat(erros.getFirst().getMessage()).doesNotContain("10.0.0.5");
                    assertThat(erros.getFirst().getMessage()).doesNotContain("pool");
                });
    }
}
