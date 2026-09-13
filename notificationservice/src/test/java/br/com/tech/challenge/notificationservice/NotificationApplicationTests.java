package br.com.tech.challenge.notificationservice;

import br.com.tech.challenge.notificationservice.support.PostgresTestcontainers;
import br.com.tech.challenge.notificationservice.support.RabbitTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import({PostgresTestcontainers.class, RabbitTestcontainers.class})
class NotificationApplicationTests {

	@Test
	void contextLoads() {
	}

}
