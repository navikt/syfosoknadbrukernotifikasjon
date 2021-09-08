package no.nav.helse.flex

import no.nav.brukernotifikasjon.schemas.Done
import no.nav.brukernotifikasjon.schemas.Nokkel
import no.nav.brukernotifikasjon.schemas.Oppgave
import no.nav.helse.flex.kafka.DONE_TOPIC
import no.nav.helse.flex.kafka.OPPGAVE_TOPIC
import org.amshove.kluent.shouldBeEmpty
import org.apache.kafka.clients.consumer.Consumer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

private class PostgreSQLContainer11 : PostgreSQLContainer<PostgreSQLContainer11>("postgres:11.4-alpine")

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest
abstract class AbstractContainerBaseTest {

    companion object {
        init {
            PostgreSQLContainer11().also {
                it.start()
                System.setProperty("spring.datasource.url", it.jdbcUrl)
                System.setProperty("spring.datasource.username", it.username)
                System.setProperty("spring.datasource.password", it.password)
            }

            KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:6.1.0")).also {
                it.start()
                System.setProperty("on-prem-kafka.bootstrap-servers", it.bootstrapServers)
                System.setProperty("KAFKA_BROKERS", it.bootstrapServers)
            }
        }
    }

    @Autowired
    lateinit var oppgaveKafkaConsumer: Consumer<Nokkel, Oppgave>

    @Autowired
    lateinit var doneKafkaConsumer: Consumer<Nokkel, Done>

    @AfterAll
    fun `Vi leser oppgave kafka topicet og feil hvis noe finnes og slik at subklassetestene leser alt`() {
        oppgaveKafkaConsumer.hentProduserteRecords().shouldBeEmpty()
    }

    @AfterAll
    fun `Vi leser done kafka topicet og feil hvis noe finnes og slik at subklassetestene leser alt`() {
        doneKafkaConsumer.hentProduserteRecords().shouldBeEmpty()
    }

    @BeforeAll
    fun `Vi leser oppgave og done kafka topicet og feiler om noe eksisterer`() {
        oppgaveKafkaConsumer.subscribeHvisIkkeSubscribed(OPPGAVE_TOPIC)
        doneKafkaConsumer.subscribeHvisIkkeSubscribed(DONE_TOPIC)

        oppgaveKafkaConsumer.hentProduserteRecords().shouldBeEmpty()
        doneKafkaConsumer.hentProduserteRecords().shouldBeEmpty()
    }

    fun Any.serialisertTilString(): String = objectMapper.writeValueAsString(this)
}
