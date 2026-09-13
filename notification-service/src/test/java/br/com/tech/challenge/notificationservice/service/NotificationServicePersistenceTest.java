package br.com.tech.challenge.notificationservice.service;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.dto.AppointmentEventStatus;
import br.com.tech.challenge.notificationservice.model.Notification;
import br.com.tech.challenge.notificationservice.notification.NotificationSender;
import br.com.tech.challenge.notificationservice.repository.NotificationRepository;
import br.com.tech.challenge.notificationservice.support.PostgresTestcontainers;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Processamento contra um Postgres real. O teste com mock puro nao prova nada sobre a colisao de
 * event_id: sem banco, o UNIQUE nunca dispara de verdade.
 *
 * Com a deduplicacao funcionando, o segundo processamento encontraria a linha e nunca chegaria ao
 * banco. Por isso o repositorio delega ao real em tudo, menos no findByEventId: as duas checagens
 * de deduplicacao devolvem vazio, reproduzindo a corrida em que o outro consumer ainda nao tinha
 * commitado, e a releitura apos a colisao delega ao real.
 *
 * O @Transactional(NOT_SUPPORTED) desliga a transacao que o @DataJpaTest abre por padrao, para que
 * cada save rode na propria transacao, como em producao.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainers.class, NotificationServicePersistenceTest.CorridaConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationServicePersistenceTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class CorridaConfig {

        @Bean
        NotificationSender notificationSender() {
            return mock(NotificationSender.class);
        }

        @Bean
        NotificationService notificationService(NotificationRepository real, NotificationSender sender) {
            NotificationRepository naCorrida =
                    mock(NotificationRepository.class, AdditionalAnswers.delegatesTo(real));
            // doReturn nao executa o metodo real durante o stub, ao contrario de when(...)
            doReturn(Optional.empty())
                    .doReturn(Optional.empty())
                    .doAnswer(invocation -> real.findByEventId(invocation.getArgument(0)))
                    .when(naCorrida).findByEventId(any());

            return new NotificationService(
                    naCorrida, sender, Validation.buildDefaultValidatorFactory().getValidator());
        }
    }

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private NotificationService service;

    @Autowired
    private NotificationSender sender;

    @AfterEach
    void limpar() {
        repository.deleteAll();
    }

    @Test
    void devolveALinhaExistenteQuandoAInsercaoColideComAConstraintReal() {
        AppointmentEvent evento = new AppointmentEvent(
                UUID.randomUUID(), AppointmentEventStatus.SCHEDULED, Instant.parse("2026-09-12T14:00:00Z"),
                42L, 10L, "Maria Souza", 7L, "Dr. Joao Lima",
                LocalDateTime.of(2030, 9, 10, 14, 30), "Consulta de rotina");

        Notification primeira = service.processAppointmentEvent(evento);

        Notification[] segunda = new Notification[1];
        assertThatCode(() -> segunda[0] = service.processAppointmentEvent(evento))
                .doesNotThrowAnyException();

        assertThat(segunda[0].getId()).isEqualTo(primeira.getId());
        assertThat(repository.count()).isEqualTo(1);
        verify(sender, times(1)).send(any(Notification.class));
    }
}
