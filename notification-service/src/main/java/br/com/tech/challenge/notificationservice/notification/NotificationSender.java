package br.com.tech.challenge.notificationservice.notification;

import br.com.tech.challenge.notificationservice.model.Notification;

public interface NotificationSender {

    void send(Notification notification);

}
