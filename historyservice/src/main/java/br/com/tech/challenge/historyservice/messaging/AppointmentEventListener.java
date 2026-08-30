package br.com.tech.challenge.historyservice.messaging;

import br.com.tech.challenge.historyservice.dto.AppointmentEventDTO;
import br.com.tech.challenge.historyservice.services.HistoryIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consome AppointmentEvent publicado pelo appointment-service.
 *
 * Qualquer excecao lancada daqui rejeita a mensagem. Como
 * spring.rabbitmq.listener.simple.default-requeue-rejected=false, ela vai direto para a DLQ
 * (history.queue.dlq) em vez de entrar em loop de reentrega.
 */
@Slf4j
@Component
public class AppointmentEventListener {

    private final HistoryIngestionService ingestionService;

    public AppointmentEventListener(HistoryIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @RabbitListener(queues = "${app.rabbitmq.queue}")
    public void onAppointmentEvent(AppointmentEventDTO evento) {
        log.info("AppointmentEvent recebido: eventId={} appointmentId={} status={}",
                evento.eventId(), evento.appointmentId(), evento.eventStatus());
        ingestionService.ingest(evento);
    }
}
