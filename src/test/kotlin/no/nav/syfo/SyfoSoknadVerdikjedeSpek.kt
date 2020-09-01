package no.nav.syfo

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.jackson.jackson
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.util.KtorExperimentalAPI
import io.mockk.* // ktlint-disable no-wildcard-imports
import kotlinx.coroutines.runBlocking
import no.nav.brukernotifikasjon.schemas.Beskjed
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.Topics.SYFO_SOKNAD_V2
import no.nav.syfo.application.Topics.SYFO_SOKNAD_V3
import no.nav.syfo.brukernotifkasjon.BrukernotifikasjonKafkaProducer
import no.nav.syfo.kafka.toConsumerConfig
import no.nav.syfo.kafka.toProducerConfig
import no.nav.syfo.soknad.kafka.SyfoSoknadConsumer
import no.nav.syfo.soknad.service.SyfoSoknadService
import no.nav.syfo.testutil.stopApplicationNarKafkaTopicErLest
import org.amshove.kluent.shouldEqual
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.Network
import java.util.* // ktlint-disable no-wildcard-imports

@KtorExperimentalAPI
object SyfoSoknadVerdikjedeSpek : Spek({

    val brukernotifikasjonKafkaProducer = mockk<BrukernotifikasjonKafkaProducer>()
    val env = mockk<Environment>()

    beforeEachTest {
        clearAllMocks()
        every { env.serviceuserUsername } returns "srvsyfosokbrukerntf"
        every { brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonMelding(any(), any()) } just Runs
    }

    describe("Test hele verdikjeden") {
        with(TestApplicationEngine()) {

            val kafka = KafkaContainer().withNetwork(Network.newNetwork())
            kafka.start()

            val kafkaConfig = Properties()
            kafkaConfig.let {
                it["bootstrap.servers"] = kafka.bootstrapServers
                it[ConsumerConfig.GROUP_ID_CONFIG] = "groupId"
                it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
                it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
                it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
            }
            val consumerProperties = kafkaConfig.toConsumerConfig(
                "consumer", valueDeserializer = StringDeserializer::class
            )
            val producerProperties = kafkaConfig.toProducerConfig(
                "producer", valueSerializer = StringSerializer::class
            )

            val syfoSoknadProducer = KafkaProducer<String, String>(producerProperties)

            val syfoSoknadKafkaConsumer = spyk(KafkaConsumer<String, String>(consumerProperties))

            syfoSoknadKafkaConsumer.subscribe(listOf(SYFO_SOKNAD_V2, SYFO_SOKNAD_V3))

            val applicationState = ApplicationState()
            applicationState.ready = true
            applicationState.alive = true

            val syfoSoknadConsumer =
                SyfoSoknadConsumer(syfoSoknadKafkaConsumer)
            val syfoSoknadService = SyfoSoknadService(
                applicationState = applicationState,
                syfoSoknadConsumer = syfoSoknadConsumer,
                brukernotifikasjonKafkaProducer = brukernotifikasjonKafkaProducer,
                servicebruker = "srvsyfosokbrukerntf"
            )

            val fnr = "13068700000"

            start()
            application.install(ContentNegotiation) {
                jackson {
                    registerKotlinModule()
                    registerModule(JavaTimeModule())
                    configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                }
            }

            it("Syfo Soknad mottas fra kafka topic og dittnav besjed sendes ut") {

                syfoSoknadProducer.send(
                    ProducerRecord(
                        SYFO_SOKNAD_V2,
                        null,
                        fnr,
                        "{ \"soknad\": 123}",
                        listOf(RecordHeader("type", "Soknad".toByteArray()))
                    )
                )

                stopApplicationNarKafkaTopicErLest(syfoSoknadKafkaConsumer, applicationState)

                runBlocking {
                    syfoSoknadService.start()
                }

                val beskjedSlot = slot<Beskjed>()

                verify(exactly = 1) { brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonMelding(any(), capture(beskjedSlot)) }
                beskjedSlot.captured.getFodselsnummer() shouldEqual fnr
                beskjedSlot.captured.getSikkerhetsnivaa() shouldEqual 4
                beskjedSlot.captured.getTekst() shouldEqual "NAV har behandlet søknad om sykepenger"
                beskjedSlot.captured.getLink() shouldEqual "#"
            }
        }
    }
})
