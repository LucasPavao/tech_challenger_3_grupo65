package br.com.tech.challenge.historyservice;

import br.com.tech.challenge.historyservice.support.PostgresTestcontainers;
import br.com.tech.challenge.historyservice.support.RabbitTestcontainers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import({PostgresTestcontainers.class, RabbitTestcontainers.class})
class HistoryApplicationTests {

	@Test
	void contextLoads() {
	}

}
