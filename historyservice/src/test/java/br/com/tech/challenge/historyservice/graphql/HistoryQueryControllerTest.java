package br.com.tech.challenge.historyservice.graphql;

import java.util.List;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.dto.MedicalRecordResponse;
import br.com.tech.challenge.historyservice.services.MedicalHistoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@GraphQlTest(HistoryQueryController.class)
class HistoryQueryControllerTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private MedicalHistoryQueryService queryService;

    private MedicalRecordResponse resposta(String appointmentId, AppointmentEventStatus eventStatus) {
        return new MedicalRecordResponse("1", appointmentId, "10", "Maria Souza", "7",
                "Dr. Joao Lima", "Consulta de rotina", "2026-09-05T09:00:00",
                eventStatus, "2026-08-30T14:32:10Z");
    }

    @Test
    void patientHistoryDevolveOsCamposDoRegistro() {
        when(queryService.patientHistory(10L))
                .thenReturn(List.of(resposta("42", AppointmentEventStatus.COMPLETED)));

        graphQlTester.document("""
                        query {
                          patientHistory(patientId: 10) {
                            appointmentId
                            patientName
                            doctorName
                            appointmentDate
                            eventStatus
                            occurredAt
                          }
                        }
                        """)
                .execute()
                .path("patientHistory[0].appointmentId").entity(String.class).isEqualTo("42")
                .path("patientHistory[0].patientName").entity(String.class).isEqualTo("Maria Souza")
                .path("patientHistory[0].doctorName").entity(String.class).isEqualTo("Dr. Joao Lima")
                .path("patientHistory[0].appointmentDate").entity(String.class).isEqualTo("2026-09-05T09:00:00")
                .path("patientHistory[0].eventStatus").entity(String.class).isEqualTo("COMPLETED")
                .path("patientHistory[0].occurredAt").entity(String.class).isEqualTo("2026-08-30T14:32:10Z");
    }

    @Test
    void patientHistoryConverteOArgumentoIdParaLong() {
        when(queryService.patientHistory(10L)).thenReturn(List.of());

        graphQlTester.document("{ patientHistory(patientId: 10) { appointmentId } }")
                .execute()
                .path("patientHistory").entityList(Object.class).hasSize(0);

        verify(queryService).patientHistory(10L);
    }

    @Test
    void patientHistoryDevolveListaVaziaSemErro() {
        when(queryService.patientHistory(404L)).thenReturn(List.of());

        graphQlTester.document("{ patientHistory(patientId: 404) { appointmentId } }")
                .execute()
                .errors().verify()
                .path("patientHistory").entityList(Object.class).hasSize(0);
    }

    @Test
    void devolveApenasOsCamposPedidos() {
        when(queryService.patientHistory(10L))
                .thenReturn(List.of(resposta("42", AppointmentEventStatus.SCHEDULED)));

        graphQlTester.document("{ patientHistory(patientId: 10) { appointmentId } }")
                .execute()
                .path("patientHistory[0]").entity(java.util.Map.class)
                .satisfies(registro -> assertThat(registro).containsOnlyKeys("appointmentId"));
    }
}
