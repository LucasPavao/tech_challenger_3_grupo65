package br.com.tech.challenge.notificationservice;

import br.com.tech.challenge.notificationservice.support.PostgresTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainers.class)
class NotificationSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCriaTabelaNotificationsComTodasAsColunas() {
        List<String> colunas = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'notifications'",
                String.class);

        assertThat(colunas).containsExactlyInAnyOrder(
                "id", "event_id", "event_status", "appointment_id", "patient_id",
                "message", "status", "created_at");
    }

    @Test
    void eventIdTemConstraintUnica() {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = 'notifications'
                  AND tc.constraint_type = 'UNIQUE'
                  AND kcu.column_name = 'event_id'
                """, Integer.class);

        assertThat(total).isEqualTo(1);
    }
}
