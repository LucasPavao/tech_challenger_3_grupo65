package br.com.tech.challenge.notificationservice.messaging;

import br.com.tech.challenge.notificationservice.dto.AppointmentEvent;
import br.com.tech.challenge.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationMessageListener {

    private final NotificationService notificationService;

    @RabbitListener(queues = "${app.rabbitmq.queue}")
    public void onMessage(AppointmentEvent event) {

        log.info(
                "Evento recebido: appointmentId={}, eventType={}",
                event.getAppointmentId(),
                event.getEventType()
        );

        notificationService.processAppointmentEvent(event);
    }
}
