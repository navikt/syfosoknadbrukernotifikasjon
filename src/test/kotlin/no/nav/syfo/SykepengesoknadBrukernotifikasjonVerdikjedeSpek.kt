package no.nav.syfo

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.jackson.jackson
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.util.KtorExperimentalAPI
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import no.nav.brukernotifikasjon.schemas.Done
import no.nav.brukernotifikasjon.schemas.Nokkel
import no.nav.brukernotifikasjon.schemas.Oppgave
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.Topics.SYFO_SOKNAD_V2
import no.nav.syfo.application.Topics.SYFO_SOKNAD_V3
import no.nav.syfo.brukernotifkasjon.BrukernotifikasjonKafkaProducer
import no.nav.syfo.kafka.toConsumerConfig
import no.nav.syfo.kafka.toProducerConfig
import no.nav.syfo.soknad.domene.EnkelSykepengesoknad
import no.nav.syfo.soknad.domene.Soknadsstatus
import no.nav.syfo.soknad.domene.Soknadstype
import no.nav.syfo.soknad.kafka.SyfosoknadKafkaPoller
import no.nav.syfo.soknad.service.SykepengesoknadBrukernotifikasjonService
import no.nav.syfo.testutil.stopApplicationNarKafkaTopicErLest
import org.amshove.kluent.shouldEqual
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.Network
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Properties
import java.util.UUID

@KtorExperimentalAPI
object SykepengesoknadBrukernotifikasjonVerdikjedeSpek : Spek({

    val brukernotifikasjonKafkaProducer = mockk<BrukernotifikasjonKafkaProducer>()
    val env = mockk<Environment>()

    val systembruker = "srvsyfosokbrukerntf"

    beforeEachTest {
        clearAllMocks()
        every { env.serviceuserUsername } returns systembruker
        every { env.isProd() } returns false
        every { env.sykepengesoknadFrontend } returns "https://tjenester-q1.nav.no/sykepengesoknad/soknader/"
        every { brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonOppgave(any(), any()) } just Runs
        every { brukernotifikasjonKafkaProducer.sendDonemelding(any(), any()) } just Runs
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

            val syfosoknadKafkaPoller = SyfosoknadKafkaPoller(syfoSoknadKafkaConsumer)
            val syfoSoknadService = SykepengesoknadBrukernotifikasjonService(
                applicationState = applicationState,
                syfosoknadKafkaPoller = syfosoknadKafkaPoller,
                brukernotifikasjonKafkaProducer = brukernotifikasjonKafkaProducer,
                servicebruker = systembruker,
                environment = env
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

            it("NY arbeidstaker søknad mottas fra kafka topic og dittnav oppgave sendes ut") {

                val id = UUID.randomUUID().toString()
                val sykmeldingId = UUID.randomUUID().toString()
                val enkelSoknad = EnkelSykepengesoknad(
                    id = id,
                    status = Soknadsstatus.NY,
                    type = Soknadstype.ARBEIDSTAKERE,
                    opprettet = LocalDateTime.now(),
                    fnr = fnr,
                    sykmeldingId = sykmeldingId
                )

                syfoSoknadProducer.send(
                    ProducerRecord(
                        SYFO_SOKNAD_V2,
                        null,
                        id,
                        enkelSoknad.tilJson()
                    )
                )

                stopApplicationNarKafkaTopicErLest(syfoSoknadKafkaConsumer, applicationState)

                runBlocking {
                    syfoSoknadService.start()
                }

                val beskjedSlot = slot<Oppgave>()
                val nokkelSlot = slot<Nokkel>()

                verify(exactly = 1) { brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonOppgave(capture(nokkelSlot), capture(beskjedSlot)) }
                verify(exactly = 0) { brukernotifikasjonKafkaProducer.sendDonemelding(any(), any()) }

                nokkelSlot.captured.getEventId() shouldEqual id
                nokkelSlot.captured.getSystembruker() shouldEqual systembruker

                beskjedSlot.captured.getFodselsnummer() shouldEqual fnr
                beskjedSlot.captured.getSikkerhetsnivaa() shouldEqual 4
                beskjedSlot.captured.getTekst() shouldEqual "Du har en søknad om sykepenger du må fylle ut"
                beskjedSlot.captured.getLink() shouldEqual "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"
                beskjedSlot.captured.getGrupperingsId() shouldEqual sykmeldingId
            }

            it("NY, men veldig gammel, arbeidstaker mottas fra kafka topic, ingen oppgave sendes ut") {
                applicationState.ready = true
                applicationState.alive = true
                val id = UUID.randomUUID().toString()
                val sykmeldingId = UUID.randomUUID().toString()
                val enkelSoknad = EnkelSykepengesoknad(
                    id = id,
                    status = Soknadsstatus.NY,
                    type = Soknadstype.ARBEIDSTAKERE,
                    opprettet = LocalDate.of(2019, 1, 1).atStartOfDay(),
                    fnr = fnr,
                    sykmeldingId = sykmeldingId
                )

                syfoSoknadProducer.send(
                    ProducerRecord(
                        SYFO_SOKNAD_V2,
                        null,
                        id,
                        enkelSoknad.tilJson()
                    )
                )

                stopApplicationNarKafkaTopicErLest(syfoSoknadKafkaConsumer, applicationState)

                runBlocking {
                    syfoSoknadService.start()
                }

                verify(exactly = 0) { brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonOppgave(any(), any()) }
                verify(exactly = 0) { brukernotifikasjonKafkaProducer.sendDonemelding(any(), any()) }
            }

            it("SENDT, arbeidsledig søknad mottas fra kafka topic, Done melding sendes ut") {
                applicationState.ready = true
                applicationState.alive = true
                val id = UUID.randomUUID().toString()
                val sykmeldingId = UUID.randomUUID().toString()
                val enkelSoknad = EnkelSykepengesoknad(
                    id = id,
                    status = Soknadsstatus.SENDT,
                    type = Soknadstype.ARBEIDSLEDIG,
                    opprettet = LocalDateTime.now(),
                    fnr = fnr,
                    sykmeldingId = sykmeldingId
                )

                syfoSoknadProducer.send(
                    ProducerRecord(
                        SYFO_SOKNAD_V2,
                        null,
                        id,
                        enkelSoknad.tilJson()
                    )
                )

                stopApplicationNarKafkaTopicErLest(syfoSoknadKafkaConsumer, applicationState)

                runBlocking {
                    syfoSoknadService.start()
                }

                val doneSlot = slot<Done>()
                val nokkelSlot = slot<Nokkel>()

                verify(exactly = 0) { brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonOppgave(any(), any()) }
                verify(exactly = 1) { brukernotifikasjonKafkaProducer.sendDonemelding(capture(nokkelSlot), capture(doneSlot)) }

                nokkelSlot.captured.getEventId() shouldEqual id
                nokkelSlot.captured.getSystembruker() shouldEqual systembruker
                doneSlot.captured.getFodselsnummer() shouldEqual fnr
                doneSlot.captured.getGrupperingsId() shouldEqual sykmeldingId
            }

            it("SENDT, utenlandsøknad mottas fra kafka topic, Done melding sendes ut") {
                applicationState.ready = true
                applicationState.alive = true
                val id = UUID.randomUUID().toString()
                val sykmeldingId = UUID.randomUUID().toString()
                val enkelSoknad = EnkelSykepengesoknad(
                    id = id,
                    status = Soknadsstatus.SENDT,
                    type = Soknadstype.OPPHOLD_UTLAND,
                    opprettet = LocalDateTime.now(),
                    fnr = fnr,
                    sykmeldingId = sykmeldingId
                )

                syfoSoknadProducer.send(
                    ProducerRecord(
                        SYFO_SOKNAD_V2,
                        null,
                        id,
                        enkelSoknad.tilJson()
                    )
                )

                stopApplicationNarKafkaTopicErLest(syfoSoknadKafkaConsumer, applicationState)

                runBlocking {
                    syfoSoknadService.start()
                }

                val doneSlot = slot<Done>()
                val nokkelSlot = slot<Nokkel>()

                verify(exactly = 0) { brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonOppgave(any(), any()) }
                verify(exactly = 0) { brukernotifikasjonKafkaProducer.sendDonemelding(capture(nokkelSlot), capture(doneSlot)) }
            }
        }
    }
})

private fun EnkelSykepengesoknad.tilJson(): String {
    return objectMapper.writeValueAsString(this)
}
