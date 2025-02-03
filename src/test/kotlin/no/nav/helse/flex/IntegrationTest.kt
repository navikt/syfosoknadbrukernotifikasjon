package no.nav.helse.flex

import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.helse.flex.brukernotifikasjon.BrukernotifikasjonOpprettelse
import no.nav.helse.flex.brukernotifikasjon.BrukernotifikasjonRepository
import no.nav.helse.flex.domene.EnkelSykepengesoknad
import no.nav.helse.flex.domene.Soknadsstatus
import no.nav.helse.flex.domene.Soknadstype
import no.nav.helse.flex.kafka.SYKEPENGESOKNAD_TOPIC
import no.nav.helse.flex.util.osloZone
import no.nav.tms.varsel.action.EksternKanal
import no.nav.tms.varsel.action.Sensitivitet
import no.nav.tms.varsel.builder.VarselActionBuilder
import org.amshove.kluent.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
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
    val omFireDager = OffsetDateTime.now().plusDays(4).toInstant()
    val omNiDager = OffsetDateTime.now().plusDays(9).toInstant()

    @Test
    fun `NY arbeidstaker søknad mottas fra kafka topic og dittnav oppgave sendes ut med eksternt varsel`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val enkelSoknad =
            EnkelSykepengesoknad(
                id = id,
                status = Soknadsstatus.NY,
                type = Soknadstype.ARBEIDSTAKERE,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString(),
            ),
        )

        // Håndterer duplikat
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString(),
            ),
        )

        await().until {
            val tilUtsendelse =
                brukernotifikasjonRepository.findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(
                    omFireDager,
                )
            tilUtsendelse.size == 1
        }
        brukernotifikasjonRepository.findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(Instant.now())
            .shouldBeEmpty()

        brukernotifikasjonOpprettelse.opprettBrukernotifikasjoner(omFireDager)

        val oppgaver = varslingConsumer.ventPåRecords(antall = 1)
        varslingConsumer.ventPåRecords(antall = 0)

        oppgaver.shouldHaveSize(1)

        val nokkel = oppgaver[0].key()
        nokkel shouldBeEqualTo id

        val oppgave = oppgaver[0].value().tilOpprettVarselInstance()
        oppgave.sensitivitet shouldBeEqualTo Sensitivitet.High
        oppgave.ident shouldBeEqualTo fnr
        oppgave.tekster.first().tekst shouldBeEqualTo "Du har en søknad om sykepenger du må fylle ut"
        oppgave.link shouldBeEqualTo "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"
        oppgave.eksternVarsling.shouldNotBeNull()
        oppgave.eksternVarsling!!.prefererteKanaler shouldBeEqualTo listOf(EksternKanal.SMS)
        oppgave.eksternVarsling!!.smsVarslingstekst.shouldBeNull()
        oppgave.produsent!!.namespace shouldBeEqualTo "flex"
        oppgave.produsent!!.cluster shouldBeEqualTo "test-gcp"
        oppgave.produsent!!.appnavn shouldBeEqualTo "syfosoknadbrukernotifikasjon"

        val brukernotifikasjonDb = brukernotifikasjonRepository.findByIdOrNull(id)!!
        brukernotifikasjonDb.grupperingsid shouldBeEqualTo sykmeldingId
        brukernotifikasjonDb.soknadsid shouldBeEqualTo id
        brukernotifikasjonDb.fnr shouldBeEqualTo fnr
        brukernotifikasjonDb.oppgaveSendt.shouldNotBeNull()
        brukernotifikasjonDb.doneSendt.shouldBeNull()
    }

    @Test
    fun `SENDT søknad uten innslag i db får ikke done oppgave`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val enkelSoknad =
            EnkelSykepengesoknad(
                id = id,
                status = Soknadsstatus.SENDT,
                type = Soknadstype.ARBEIDSTAKERE,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString(),
            ),
        )

        varslingConsumer.ventPåRecords(antall = 0)
    }

    @Test
    fun `NY og SENDT søknad mottas fra kafka topic og dittnav oppgave og done melding sendes ut`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val enkelSoknad =
            EnkelSykepengesoknad(
                id = id,
                status = Soknadsstatus.NY,
                type = Soknadstype.ARBEIDSTAKERE,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString(),
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
        val sendtSoknad = enkelSoknad.copy(status = Soknadsstatus.SENDT)
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
        oppgaveNokkel shouldBeEqualTo id

        val oppgaveString = oppgaver.first().value()
        val oppgave = oppgaveString.tilOpprettVarselInstance()

        oppgave.sensitivitet shouldBeEqualTo Sensitivitet.High
        oppgave.tekster.first().tekst shouldBeEqualTo "Du har en søknad om sykepenger du må fylle ut"
        oppgave.link shouldBeEqualTo "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"
        oppgave.ident shouldBeEqualTo fnr

        val doneNokkel = dones.first().key()
        val done = dones.first().value().tilInaktiverVarselInstance()

        doneNokkel shouldBeEqualTo id
        done.varselId shouldBeEqualTo id
        done.produsent!!.appnavn shouldBeEqualTo "syfosoknadbrukernotifikasjon"
        done.produsent!!.cluster shouldBeEqualTo "test-gcp"
        done.produsent!!.namespace shouldBeEqualTo "flex"
    }

    @Test
    fun `NY og SENDT reisetilskudd mottas fra kafka topic og dittnav oppgave og done melding sendes ut`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val enkelSoknad =
            EnkelSykepengesoknad(
                id = id,
                status = Soknadsstatus.NY,
                type = Soknadstype.REISETILSKUDD,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString(),
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

        val sendtSoknad = enkelSoknad.copy(status = Soknadsstatus.SENDT)
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

        oppgaveNokkel shouldBeEqualTo id

        oppgave.varselId shouldBeEqualTo id
        oppgave.sensitivitet shouldBeEqualTo Sensitivitet.High
        oppgave.ident shouldBeEqualTo fnr
        oppgave.tekster.first().tekst shouldBeEqualTo "Du har en søknad om reisetilskudd du må fylle ut"
        oppgave.link shouldBeEqualTo "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"

        val doneNokkel = dones.first().key()
        val done = dones.first().value().tilInaktiverVarselInstance()

        doneNokkel shouldBeEqualTo id
        done.varselId shouldBeEqualTo id
        done.produsent!!.appnavn shouldBeEqualTo "syfosoknadbrukernotifikasjon"
        done.produsent!!.cluster shouldBeEqualTo "test-gcp"
        done.produsent!!.namespace shouldBeEqualTo "flex"
    }

    @Test
    fun `Ustendendelse avbrytes hvis SENDT melding kommer før vi sender brukernotifikasjonen`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val enkelSoknad =
            EnkelSykepengesoknad(
                id = id,
                status = Soknadsstatus.NY,
                type = Soknadstype.ARBEIDSTAKERE,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString(),
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

        val sendtSoknad = enkelSoknad.copy(status = Soknadsstatus.SENDT)
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

    fun String.tilOpprettVarselInstance(): VarselActionBuilder.OpprettVarselInstance {
        return objectMapper.readValue(this)
    }

    fun String.tilInaktiverVarselInstance(): VarselActionBuilder.InaktiverVarselInstance {
        return objectMapper.readValue(this)
    }

    @Test
    fun `NY og SENDT OPPHOLD_UTLAND mottas fra kafka topic og dittnav oppgave og done melding sendes ut`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val enkelSoknad =
            EnkelSykepengesoknad(
                id = id,
                status = Soknadsstatus.NY,
                type = Soknadstype.OPPHOLD_UTLAND,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString(),
            ),
        )
        await().until {
            val tilUtsendelse =
                brukernotifikasjonRepository.findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(
                    omNiDager,
                )
            tilUtsendelse.size `should be equal to` 1
            tilUtsendelse.first().utsendelsestidspunkt?.isAfter(ZonedDateTime.now(osloZone).toInstant()) `should be` true
        }
        brukernotifikasjonOpprettelse.opprettBrukernotifikasjoner(omNiDager)
        val oppgaver = varslingConsumer.ventPåRecords(antall = 1)

        // Send samme søknad

        val sendtSoknad = enkelSoknad.copy(status = Soknadsstatus.SENDT)
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

        oppgaveNokkel shouldBeEqualTo id

        oppgave.varselId shouldBeEqualTo id
        oppgave.sensitivitet shouldBeEqualTo Sensitivitet.High
        oppgave.ident shouldBeEqualTo fnr
        oppgave.tekster.first().tekst shouldBeEqualTo "Du har en søknad om å beholde sykepengene for reise utenfor EU/EØS du må fylle ut"
        oppgave.link shouldBeEqualTo "https://tjenester-q1.nav.no/sykepengesoknad/sykepengesoknad-utland"

        val doneNokkel = dones.first().key()
        val done = dones.first().value().tilInaktiverVarselInstance()

        doneNokkel shouldBeEqualTo id
        done.varselId shouldBeEqualTo id
        done.produsent!!.appnavn shouldBeEqualTo "syfosoknadbrukernotifikasjon"
        done.produsent!!.cluster shouldBeEqualTo "test-gcp"
        done.produsent!!.namespace shouldBeEqualTo "flex"
    }

    @Test
    fun `NY og SENDT friskmeldt til arbeidsformidling mottas fra kafka topic og dittnav oppgave og done melding sendes ut`() {
        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val enkelSoknad =
            EnkelSykepengesoknad(
                id = id,
                status = Soknadsstatus.NY,
                type = Soknadstype.FRISKMELDT_TIL_ARBEIDSFORMIDLING,
                fnr = fnr,
                sykmeldingId = sykmeldingId,
            )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString(),
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

        val sendtSoknad = enkelSoknad.copy(status = Soknadsstatus.SENDT)
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

        oppgaveNokkel shouldBeEqualTo id

        oppgave.varselId shouldBeEqualTo id
        oppgave.sensitivitet shouldBeEqualTo Sensitivitet.High
        oppgave.ident shouldBeEqualTo fnr
        oppgave.tekster.first().tekst shouldBeEqualTo "Du har en søknad om sykepenger du må fylle ut"
        oppgave.link shouldBeEqualTo "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"

        val doneNokkel = dones.first().key()
        val done = dones.first().value().tilInaktiverVarselInstance()

        doneNokkel shouldBeEqualTo id
        done.varselId shouldBeEqualTo id
        done.produsent!!.appnavn shouldBeEqualTo "syfosoknadbrukernotifikasjon"
        done.produsent!!.cluster shouldBeEqualTo "test-gcp"
        done.produsent!!.namespace shouldBeEqualTo "flex"
    }
}
