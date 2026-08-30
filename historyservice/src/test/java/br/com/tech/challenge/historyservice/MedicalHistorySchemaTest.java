package br.com.tech.challenge.historyservice;

import java.util.List;

import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainers.class)
class MedicalHistorySchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCriaTabelaMedicalHistoryComTodasAsColunas() {
        List<String> colunas = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'medical_history'",
                String.class);

        assertThat(colunas).containsExactlyInAnyOrder(
                "id", "event_id", "appointment_id", "patient_id", "patient_name",
                "doctor_id", "doctor_name", "description", "appointment_date",
                "event_status", "occurred_at", "recorded_at");
    }

    @Test
    void eventIdTemConstraintUnica() {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = 'medical_history'
                  AND tc.constraint_type = 'UNIQUE'
                  AND kcu.column_name = 'event_id'
                """, Integer.class);

        assertThat(total).isEqualTo(1);
    }
}
