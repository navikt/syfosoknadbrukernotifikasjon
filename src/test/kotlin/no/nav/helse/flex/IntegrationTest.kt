package no.nav.helse.flex

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.nav.helse.flex.brukernotifikasjon.BrukernotifikasjonOpprettelse
import no.nav.helse.flex.brukernotifikasjon.BrukernotifikasjonRepository
import no.nav.helse.flex.kafka.SYKEPENGESOKNAD_TOPIC
import no.nav.helse.flex.sykepengesoknad.kafka.SoknadsstatusDTO
import no.nav.helse.flex.sykepengesoknad.kafka.SoknadstypeDTO
import no.nav.helse.flex.sykepengesoknad.kafka.SykepengesoknadDTO
import no.nav.helse.flex.util.osloZone
import no.nav.tms.varsel.action.EksternKanal
import no.nav.tms.varsel.action.Sensitivitet
import no.nav.tms.varsel.builder.VarselActionBuilder
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.*

class IntegrationTest : FellesTestOppsett() {
    @Autowired
    private lateinit var aivenKafkaProducer: KafkaProducer<String, String>

    @Autowired
    private lateinit var brukernotifikasjonRepository: BrukernotifikasjonRepository

    @Autowired
    private lateinit var brukernotifikasjonOpprettelse: BrukernotifikasjonOpprettelse

    val fnr = "13068700000"
    val omFireDager: Instant = OffsetDateTime.now().plusDays(4).toInstant()
    val omNiDager: Instant = OffsetDateTime.now().plusDays(9).toInstant()

    @Test
    fun `NY arbeidstaker søknad mottas fra kafka topic og dittnav oppgave sendes ut med eksternt varsel`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val soknad =
            SykepengesoknadDTO(
                id = id,
                status = SoknadsstatusDTO.NY,
                type = SoknadstypeDTO.ARBEIDSTAKERE,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                soknad.serialisertTilString(),
            ),
        )

        // Håndterer duplikat
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                soknad.serialisertTilString(),
            ),
        )

        await().until {
            val tilUtsendelse =
                brukernotifikasjonRepository.findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(
                    omFireDager,
                )
            tilUtsendelse.size == 1
        }
        brukernotifikasjonRepository
            .findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(Instant.now())
            .shouldBeEmpty()

        brukernotifikasjonOpprettelse.opprettBrukernotifikasjoner(omFireDager)

        val oppgaver = varslingConsumer.ventPåRecords(antall = 1)
        varslingConsumer.ventPåRecords(antall = 0)

        oppgaver.shouldHaveSize(1)

        val nokkel = oppgaver[0].key()
        nokkel shouldBe id

        val oppgave = oppgaver[0].value().tilOpprettVarselInstance()
        oppgave.sensitivitet shouldBe Sensitivitet.High
        oppgave.ident shouldBe fnr
        oppgave.tekster.first().tekst shouldBe "Du har en søknad om sykepenger du må fylle ut"
        oppgave.link shouldBe "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"
        oppgave.eksternVarsling.shouldNotBeNull()
        oppgave.eksternVarsling!!.prefererteKanaler shouldBe listOf(EksternKanal.SMS)
        oppgave.eksternVarsling!!.smsVarslingstekst.shouldBeNull()
        oppgave.produsent!!.namespace shouldBe "flex"
        oppgave.produsent!!.cluster shouldBe "test-gcp"
        oppgave.produsent!!.appnavn shouldBe "syfosoknadbrukernotifikasjon"

        val brukernotifikasjonDb = brukernotifikasjonRepository.findByIdOrNull(id)!!
        brukernotifikasjonDb.grupperingsid shouldBe sykmeldingId
        brukernotifikasjonDb.soknadsid shouldBe id
        brukernotifikasjonDb.fnr shouldBe fnr
        brukernotifikasjonDb.oppgaveSendt.shouldNotBeNull()
        brukernotifikasjonDb.doneSendt.shouldBeNull()
    }

    @Test
    fun `SENDT søknad uten innslag i db får ikke done oppgave`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val soknad =
            SykepengesoknadDTO(
                id = id,
                status = SoknadsstatusDTO.SENDT,
                type = SoknadstypeDTO.ARBEIDSTAKERE,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                soknad.serialisertTilString(),
            ),
        )

        varslingConsumer.ventPåRecords(antall = 0)
    }

    @Test
    fun `NY og SENDT søknad mottas fra kafka topic og dittnav oppgave og done melding sendes ut`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val soknad =
            SykepengesoknadDTO(
                id = id,
                status = SoknadsstatusDTO.NY,
                type = SoknadstypeDTO.ARBEIDSTAKERE,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                soknad.serialisertTilString(),
            ),
        )
        await().until {
            val tilUtsendelse =
                brukernotifikasjonRepository.findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(
                    omFireDager,
                )
            tilUtsendelse.size == 1
        }
        varslingConsumer.ventPåRecords(antall = 0)

        brukernotifikasjonOpprettelse.opprettBrukernotifikasjoner(omFireDager)
        val oppgaver = varslingConsumer.ventPåRecords(antall = 1)

        // Send samme søknad
        val sendtSoknad = soknad.copy(status = SoknadsstatusDTO.SENDT)
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString(),
            ),
        )
        // Håndterer duplikat av sendt
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString(),
            ),
        )

        val dones = varslingConsumer.ventPåRecords(antall = 1)

        oppgaver.shouldHaveSize(1)
        dones.shouldHaveSize(1)

        val oppgaveNokkel = oppgaver.first().key()
        oppgaveNokkel shouldBe id

        val oppgaveString = oppgaver.first().value()
        val oppgave = oppgaveString.tilOpprettVarselInstance()

        oppgave.sensitivitet shouldBe Sensitivitet.High
        oppgave.tekster.first().tekst shouldBe "Du har en søknad om sykepenger du må fylle ut"
        oppgave.link shouldBe "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"
        oppgave.ident shouldBe fnr

        val doneNokkel = dones.first().key()
        val done = dones.first().value().tilInaktiverVarselInstance()

        doneNokkel shouldBe id
        done.varselId shouldBe id
        done.produsent!!.appnavn shouldBe "syfosoknadbrukernotifikasjon"
        done.produsent!!.cluster shouldBe "test-gcp"
        done.produsent!!.namespace shouldBe "flex"
    }

    @Test
    fun `NY og SENDT reisetilskudd mottas fra kafka topic og dittnav oppgave og done melding sendes ut`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val soknad =
            SykepengesoknadDTO(
                id = id,
                status = SoknadsstatusDTO.NY,
                type = SoknadstypeDTO.REISETILSKUDD,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                soknad.serialisertTilString(),
            ),
        )
        await().until {
            val tilUtsendelse =
                brukernotifikasjonRepository.findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(
                    omFireDager,
                )
            tilUtsendelse.size == 1
        }
        brukernotifikasjonOpprettelse.opprettBrukernotifikasjoner(omFireDager)
        val oppgaver = varslingConsumer.ventPåRecords(antall = 1)

        // Send samme søknad

        val sendtSoknad = soknad.copy(status = SoknadsstatusDTO.SENDT)
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString(),
            ),
        )
        // Håndterer duplikat av sendt
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString(),
            ),
        )

        val dones = varslingConsumer.ventPåRecords(antall = 1)

        oppgaver.shouldHaveSize(1)
        dones.shouldHaveSize(1)

        val oppgaveNokkel = oppgaver.first().key()
        val oppgave = oppgaver.first().value().tilOpprettVarselInstance()

        oppgaveNokkel shouldBe id

        oppgave.varselId shouldBe id
        oppgave.sensitivitet shouldBe Sensitivitet.High
        oppgave.ident shouldBe fnr
        oppgave.tekster.first().tekst shouldBe "Du har en søknad om reisetilskudd du må fylle ut"
        oppgave.link shouldBe "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"

        val doneNokkel = dones.first().key()
        val done = dones.first().value().tilInaktiverVarselInstance()

        doneNokkel shouldBe id
        done.varselId shouldBe id
        done.produsent!!.appnavn shouldBe "syfosoknadbrukernotifikasjon"
        done.produsent!!.cluster shouldBe "test-gcp"
        done.produsent!!.namespace shouldBe "flex"
    }

    @Test
    fun `Ustendendelse avbrytes hvis SENDT melding kommer før vi sender brukernotifikasjonen`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val soknad =
            SykepengesoknadDTO(
                id = id,
                status = SoknadsstatusDTO.NY,
                type = SoknadstypeDTO.ARBEIDSTAKERE,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                soknad.serialisertTilString(),
            ),
        )
        await().until {
            val tilUtsendelse =
                brukernotifikasjonRepository.findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(
                    omFireDager,
                )
            tilUtsendelse.size == 1
        }
        varslingConsumer.ventPåRecords(antall = 0)

        val sendtSoknad = soknad.copy(status = SoknadsstatusDTO.SENDT)
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString(),
            ),
        )

        await().until {
            val tilUtsendelse =
                brukernotifikasjonRepository.findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(
                    omFireDager,
                )
            tilUtsendelse.isEmpty()
        }
    }

    fun String.tilOpprettVarselInstance(): VarselActionBuilder.OpprettVarselInstance = objectMapper.readValue(this)

    fun String.tilInaktiverVarselInstance(): VarselActionBuilder.InaktiverVarselInstance = objectMapper.readValue(this)

    @Test
    fun `NY og SENDT OPPHOLD_UTLAND mottas fra kafka topic og dittnav oppgave og done melding sendes ut`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val soknad =
            SykepengesoknadDTO(
                id = id,
                status = SoknadsstatusDTO.NY,
                type = SoknadstypeDTO.OPPHOLD_UTLAND,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                soknad.serialisertTilString(),
            ),
        )
        await().until {
            val tilUtsendelse =
                brukernotifikasjonRepository.findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(
                    omNiDager,
                )
            tilUtsendelse.size shouldBe 1
            tilUtsendelse.first().utsendelsestidspunkt?.isAfter(ZonedDateTime.now(osloZone).toInstant()) shouldBe true
        }
        brukernotifikasjonOpprettelse.opprettBrukernotifikasjoner(omNiDager)
        val oppgaver = varslingConsumer.ventPåRecords(antall = 1)

        // Send samme søknad

        val sendtSoknad = soknad.copy(status = SoknadsstatusDTO.SENDT)
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString(),
            ),
        )
        // Håndterer duplikat av sendt
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString(),
            ),
        )

        val dones = varslingConsumer.ventPåRecords(antall = 1)

        oppgaver.shouldHaveSize(1)
        dones.shouldHaveSize(1)

        val oppgaveNokkel = oppgaver.first().key()
        val oppgave = oppgaver.first().value().tilOpprettVarselInstance()

        oppgaveNokkel shouldBe id

        oppgave.varselId shouldBe id
        oppgave.sensitivitet shouldBe Sensitivitet.High
        oppgave.ident shouldBe fnr
        oppgave.tekster.first().tekst shouldBe "Du har en søknad om å beholde sykepengene for reise utenfor EU/EØS du må fylle ut"
        oppgave.link shouldBe "https://tjenester-q1.nav.no/sykepengesoknad/sykepengesoknad-utland"

        val doneNokkel = dones.first().key()
        val done = dones.first().value().tilInaktiverVarselInstance()

        doneNokkel shouldBe id
        done.varselId shouldBe id
        done.produsent!!.appnavn shouldBe "syfosoknadbrukernotifikasjon"
        done.produsent!!.cluster shouldBe "test-gcp"
        done.produsent!!.namespace shouldBe "flex"
    }

    @Test
    fun `NY og SENDT friskmeldt til arbeidsformidling mottas fra kafka topic og dittnav oppgave og done melding sendes ut`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val soknad =
            SykepengesoknadDTO(
                id = id,
                status = SoknadsstatusDTO.NY,
                type = SoknadstypeDTO.FRISKMELDT_TIL_ARBEIDSFORMIDLING,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                soknad.serialisertTilString(),
            ),
        )
        await().until {
            val tilUtsendelse =
                brukernotifikasjonRepository.findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(
                    omFireDager,
                )
            tilUtsendelse.size == 1
        }
        brukernotifikasjonOpprettelse.opprettBrukernotifikasjoner(omFireDager)
        val oppgaver = varslingConsumer.ventPåRecords(antall = 1)

        // Send samme søknad

        val sendtSoknad = soknad.copy(status = SoknadsstatusDTO.SENDT)
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString(),
            ),
        )
        // Håndterer duplikat av sendt
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString(),
            ),
        )

        val dones = varslingConsumer.ventPåRecords(antall = 1)

        oppgaver.shouldHaveSize(1)
        dones.shouldHaveSize(1)

        val oppgaveNokkel = oppgaver.first().key()
        val oppgave = oppgaver.first().value().tilOpprettVarselInstance()

        oppgaveNokkel shouldBe id

        oppgave.varselId shouldBe id
        oppgave.sensitivitet shouldBe Sensitivitet.High
        oppgave.ident shouldBe fnr
        oppgave.tekster.first().tekst shouldBe "Du har en søknad om sykepenger du må fylle ut"
        oppgave.link shouldBe "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"

        val doneNokkel = dones.first().key()
        val done = dones.first().value().tilInaktiverVarselInstance()

        doneNokkel shouldBe id
        done.varselId shouldBe id
        done.produsent!!.appnavn shouldBe "syfosoknadbrukernotifikasjon"
        done.produsent!!.cluster shouldBe "test-gcp"
        done.produsent!!.namespace shouldBe "flex"
    }
}
