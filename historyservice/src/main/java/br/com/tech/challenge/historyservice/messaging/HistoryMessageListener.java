package br.com.tech.challenge.historyservice.messaging;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class HistoryMessageListener {

    @RabbitListener(queues = "${app.rabbitmq.queue}")
    public void onMessage(Map<String, Object> payload) {
        log.info("Mensagem recebida da fila: {}", payload);
    }
}
