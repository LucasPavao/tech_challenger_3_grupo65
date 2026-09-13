package br.com.tech.challenge.notificationservice.messaging;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consome eventos de consultas publicados pelo appointment-service.
 *
 * Qualquer exceção lançada daqui rejeita a mensagem. Como
 * spring.rabbitmq.listener.simple.default-requeue-rejected=false, ela vai direto para a DLQ
 * (notification.queue.dlq) em vez de entrar em loop de reentrega.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationMessageListener {

    private final NotificationService notificationService;

    @RabbitListener(queues = "${app.rabbitmq.queue}")
    public void onAppointmentEvent(AppointmentEvent event) {
        log.info(
                "Evento de appointment recebido: appointmentId={}, eventStatus={}, eventId={}",
                event.appointmentId(),
                event.eventStatus(),
                event.eventId()
        );

        notificationService.processAppointmentEvent(event);
    }
}
