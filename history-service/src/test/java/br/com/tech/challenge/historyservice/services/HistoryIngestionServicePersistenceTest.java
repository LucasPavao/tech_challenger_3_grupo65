package br.com.tech.challenge.historyservice.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.tech.challenge.historyservice.domain.AppointmentEventStatus;
import br.com.tech.challenge.historyservice.dto.AppointmentEventDTO;
import br.com.tech.challenge.historyservice.repositories.MedicalHistoryRepository;
import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ingestao contra um Postgres real. O teste com mock puro nao prova nada sobre a colisao de
 * event_id: sem EntityManager, sem flush e sem transacao, o UNIQUE nunca dispara de verdade e o
 * commit nunca acontece.
 *
 * O service e registrado como @Bean para ganhar o proxy transacional do Spring -- construido a mao
 * o @Transactional seria ignorado e o teste passaria por engano.
 *
 * O @Transactional(NOT_SUPPORTED) desliga a transacao que o @DataJpaTest abre por padrao, para que
 * cada chamada rode na propria transacao, como em producao.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainers.class, HistoryIngestionServicePersistenceTest.IngestaoConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HistoryIngestionServicePersistenceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-30T14:32:10Z");
    private static final LocalDateTime DATA_CONSULTA = LocalDateTime.of(2026, 9, 5, 9, 0);

    @TestConfiguration(proxyBeanMethods = false)
    static class IngestaoConfig {

        @Bean
        HistoryIngestionService historyIngestionService(MedicalHistoryRepository real) {
            // Repositorio real em tudo, menos no existsByEventId: forcamos false para reproduzir a
            // corrida em que o outro consumer ainda nao havia commitado quando a checagem rodou.
            MedicalHistoryRepository naCorrida =
                    mock(MedicalHistoryRepository.class, AdditionalAnswers.delegatesTo(real));
            when(naCorrida.existsByEventId(any())).thenReturn(false);

            return new HistoryIngestionService(
                    naCorrida, Validation.buildDefaultValidatorFactory().getValidator());
        }
    }

    @Autowired
    private MedicalHistoryRepository repository;

    @Autowired
    private HistoryIngestionService service;

    @AfterEach
    void limpar() {
        repository.deleteAll();
    }

    private AppointmentEventDTO evento(UUID eventId) {
        return new AppointmentEventDTO(
                eventId, AppointmentEventStatus.SCHEDULED, OCCURRED_AT, 42L, 10L, "Maria Souza",
                7L, "Dr. Joao Lima", DATA_CONSULTA, "Consulta de rotina");
    }

    @Test
    void engoleColisaoDeEventIdContraOBancoReal() {
        UUID mesmoEventId = UUID.randomUUID();

        service.ingest(evento(mesmoEventId));

        assertThatCode(() -> service.ingest(evento(mesmoEventId)))
                .doesNotThrowAnyException();

        assertThat(repository.findByAppointmentIdOrderByOccurredAtAscIdAsc(42L)).hasSize(1);
    }
}
