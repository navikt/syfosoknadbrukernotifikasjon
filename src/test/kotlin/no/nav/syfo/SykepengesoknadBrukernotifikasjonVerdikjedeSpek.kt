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
import no.nav.syfo.brukernotifkasjon.BrukernotifikasjonKafkaProdusent
import no.nav.syfo.kafka.skapSoknadKafkaConsumer
import no.nav.syfo.soknad.domene.EnkelSykepengesoknad
import no.nav.syfo.soknad.domene.Soknadsstatus
import no.nav.syfo.soknad.domene.Soknadstype
import no.nav.syfo.soknad.kafka.SyfosoknadKafkaPoller
import no.nav.syfo.soknad.service.SykepengesoknadBrukernotifikasjonService
import no.nav.syfo.testutil.TestDB
import no.nav.syfo.testutil.stopApplicationNårAntallKafkaMeldingerErLest
import org.amshove.kluent.shouldEqual
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.Network
import org.testcontainers.utility.DockerImageName
import java.time.LocalDate
import java.util.UUID

@KtorExperimentalAPI
object SykepengesoknadBrukernotifikasjonVerdikjedeSpek : Spek({

    val brukernotifikasjonKafkaProducer = mockk<BrukernotifikasjonKafkaProdusent>()
    val env = mockk<Environment>()
    val testDb = TestDB()
    val etterDatabaseIProd = LocalDate.of(2020, 11, 6).atTime(11, 0)

    val systembruker = "srvsyfosokbrukerntf"

    val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:5.4.3"))
        .withNetwork(Network.newNetwork())
    kafka.start()

    fun setupEnvMock() {
        clearAllMocks()
        every { env.serviceuserUsername } returns systembruker
        every { env.isProd() } returns false
        every { env.kafkaSecurityProtocol } returns "PLAINTEXT"
        every { env.kafkaAutoOffsetReset } returns "earliest"
        every { env.kafkaBootstrapServers } returns kafka.bootstrapServers
        every { env.serviceuserPassword } returns "pwd"
        every { env.sykepengesoknadFrontend } returns "https://tjenester-q1.nav.no/sykepengesoknad/soknader/"
        every { brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonOppgave(any(), any()) } just Runs
        every { brukernotifikasjonKafkaProducer.sendDonemelding(any(), any()) } just Runs
    }

    setupEnvMock()

    beforeEachTest {
        setupEnvMock()
    }
    describe("Test hele verdikjeden") {
        with(TestApplicationEngine()) {

            val syfoSoknadProducer = KafkaProducer<String, String>(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java
                )
            )

            val syfoSoknadKafkaConsumer = spyk(skapSoknadKafkaConsumer(env))

            val applicationState = ApplicationState()
            applicationState.ready = true
            applicationState.alive = true

            val syfosoknadKafkaPoller = SyfosoknadKafkaPoller(syfoSoknadKafkaConsumer)
            val syfoSoknadService = SykepengesoknadBrukernotifikasjonService(
                applicationState = applicationState,
                syfosoknadKafkaPoller = syfosoknadKafkaPoller,
                brukernotifikasjonKafkaProdusent = brukernotifikasjonKafkaProducer,
                servicebruker = systembruker,
                environment = env,
                database = testDb
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
                    opprettet = etterDatabaseIProd,
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

                // Håndterer duplikat
                syfoSoknadProducer.send(
                    ProducerRecord(
                        SYFO_SOKNAD_V2,
                        null,
                        id,
                        enkelSoknad.tilJson()
                    )
                )

                stopApplicationNårAntallKafkaMeldingerErLest(syfoSoknadKafkaConsumer, applicationState, 2)
                applicationState.alive = true
                applicationState.ready = true

                runBlocking {
                    syfoSoknadService.start()
                }

                val beskjedSlot = slot<Oppgave>()
                val nokkelSlot = slot<Nokkel>()

                verify(exactly = 1) {
                    brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonOppgave(
                        capture(
                            nokkelSlot
                        ),
                        capture(beskjedSlot)
                    )
                }
                verify(exactly = 0) { brukernotifikasjonKafkaProducer.sendDonemelding(any(), any()) }

                nokkelSlot.captured.getEventId() shouldEqual id
                nokkelSlot.captured.getSystembruker() shouldEqual systembruker

                beskjedSlot.captured.getFodselsnummer() shouldEqual fnr
                beskjedSlot.captured.getSikkerhetsnivaa() shouldEqual 4
                beskjedSlot.captured.getTekst() shouldEqual "Du har en søknad om sykepenger du må fylle ut"
                beskjedSlot.captured.getLink() shouldEqual "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"
                beskjedSlot.captured.getGrupperingsId() shouldEqual sykmeldingId
            }

            it("SENDT søknad fra periode uten database får done oppgave selv uten innslag i db") {
                applicationState.ready = true
                applicationState.alive = true
                val id = UUID.randomUUID().toString()
                val sykmeldingId = UUID.randomUUID().toString()
                val enkelSoknad = EnkelSykepengesoknad(
                    id = id,
                    status = Soknadsstatus.SENDT,
                    type = Soknadstype.ARBEIDSTAKERE,
                    opprettet = LocalDate.of(2020, 11, 2).atTime(11, 0),
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

                stopApplicationNårAntallKafkaMeldingerErLest(syfoSoknadKafkaConsumer, applicationState, 1)
                applicationState.alive = true
                applicationState.ready = true

                runBlocking {
                    syfoSoknadService.start()
                }

                val doneSlot = slot<Done>()
                val nokkelSlot = slot<Nokkel>()
                verify(exactly = 0) { brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonOppgave(any(), any()) }
                verify(exactly = 1) {
                    brukernotifikasjonKafkaProducer.sendDonemelding(
                        capture(nokkelSlot),
                        capture(doneSlot)
                    )
                }

                nokkelSlot.captured.getEventId() shouldEqual id
                nokkelSlot.captured.getSystembruker() shouldEqual systembruker
                doneSlot.captured.getFodselsnummer() shouldEqual fnr
                doneSlot.captured.getGrupperingsId() shouldEqual sykmeldingId
            }

            it("SENDT søknad uten innslag i db får ikke done oppgave") {
                applicationState.ready = true
                applicationState.alive = true
                val id = UUID.randomUUID().toString()
                val sykmeldingId = UUID.randomUUID().toString()
                val enkelSoknad = EnkelSykepengesoknad(
                    id = id,
                    status = Soknadsstatus.SENDT,
                    type = Soknadstype.ARBEIDSTAKERE,
                    opprettet = etterDatabaseIProd,
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

                stopApplicationNårAntallKafkaMeldingerErLest(syfoSoknadKafkaConsumer, applicationState, 1)
                applicationState.alive = true
                applicationState.ready = true

                runBlocking {
                    syfoSoknadService.start()
                }

                verify(exactly = 0) { brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonOppgave(any(), any()) }
                verify(exactly = 0) { brukernotifikasjonKafkaProducer.sendDonemelding(any(), any()) }
            }

            it("NY og SENDT søknad mottas fra kafka topic og dittnav oppgave og done melding sendes ut") {

                val id = UUID.randomUUID().toString()
                val sykmeldingId = UUID.randomUUID().toString()
                val enkelSoknad = EnkelSykepengesoknad(
                    id = id,
                    status = Soknadsstatus.NY,
                    type = Soknadstype.ARBEIDSTAKERE,
                    opprettet = etterDatabaseIProd,
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

                // Send samme søknad

                val sendtSoknad = enkelSoknad.copy(status = Soknadsstatus.SENDT)
                syfoSoknadProducer.send(
                    ProducerRecord(
                        SYFO_SOKNAD_V2,
                        null,
                        id,
                        sendtSoknad.tilJson()
                    )
                )
                // Håndterer duplikat av sendt
                syfoSoknadProducer.send(
                    ProducerRecord(
                        SYFO_SOKNAD_V2,
                        null,
                        id,
                        sendtSoknad.tilJson()
                    )
                )

                stopApplicationNårAntallKafkaMeldingerErLest(syfoSoknadKafkaConsumer, applicationState, 3)
                applicationState.alive = true
                applicationState.ready = true

                runBlocking {
                    syfoSoknadService.start()
                }

                val beskjedSlot = slot<Oppgave>()
                val nokkelSlot = slot<Nokkel>()

                verify(exactly = 1) {
                    brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonOppgave(
                        capture(
                            nokkelSlot
                        ),
                        capture(beskjedSlot)
                    )
                }
                val doneSlot = slot<Done>()
                val nokkelSlot2 = slot<Nokkel>()
                verify(exactly = 1) {
                    brukernotifikasjonKafkaProducer.sendDonemelding(
                        capture(nokkelSlot2),
                        capture(doneSlot)
                    )
                }
                nokkelSlot.captured.getEventId() shouldEqual id
                nokkelSlot.captured.getSystembruker() shouldEqual systembruker

                beskjedSlot.captured.getFodselsnummer() shouldEqual fnr
                beskjedSlot.captured.getSikkerhetsnivaa() shouldEqual 4
                beskjedSlot.captured.getTekst() shouldEqual "Du har en søknad om sykepenger du må fylle ut"
                beskjedSlot.captured.getLink() shouldEqual "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"
                beskjedSlot.captured.getGrupperingsId() shouldEqual sykmeldingId

                nokkelSlot2.captured.getEventId() shouldEqual id
                nokkelSlot2.captured.getSystembruker() shouldEqual systembruker
                doneSlot.captured.getFodselsnummer() shouldEqual fnr
                doneSlot.captured.getGrupperingsId() shouldEqual sykmeldingId
            }
        }
    }
})

private fun EnkelSykepengesoknad.tilJson(): String {
    return objectMapper.writeValueAsString(this)
}
