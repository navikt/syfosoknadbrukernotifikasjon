package no.nav.helse.flex

import no.nav.helse.flex.db.BrukernotifikasjonRepository
import no.nav.helse.flex.domene.EnkelSykepengesoknad
import no.nav.helse.flex.domene.Soknadsstatus
import no.nav.helse.flex.domene.Soknadstype
import no.nav.helse.flex.kafka.SYKEPENGESOKNAD_TOPIC
import org.amshove.kluent.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import java.time.LocalDate
import java.util.*

class IntegrationTest : AbstractContainerBaseTest() {

    @Autowired
    private lateinit var aivenKafkaProducer: KafkaProducer<String, String>

    @Autowired
    private lateinit var brukernotifikasjonRepository: BrukernotifikasjonRepository

    val etterDatabaseIProd = LocalDate.of(2020, 11, 6).atTime(11, 0)
    val fnr = "13068700000"
    val systembruker = "brukernavnet"

    @Test
    fun `NY arbeidstaker søknad mottas fra kafka topic og dittnav oppgave sendes ut`() {
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

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString()
            )
        )

        // Håndterer duplikat
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString()
            )
        )

        val oppgaver = oppgaveKafkaConsumer.ventPåRecords(antall = 1)
        doneKafkaConsumer.ventPåRecords(antall = 0)

        oppgaver.shouldHaveSize(1)

        val nokkel = oppgaver[0].key()
        nokkel.getEventId() shouldBeEqualTo id
        nokkel.getSystembruker() shouldBeEqualTo systembruker

        val oppgave = oppgaver[0].value()

        oppgave.getFodselsnummer() shouldBeEqualTo fnr
        oppgave.getSikkerhetsnivaa() shouldBeEqualTo 4
        oppgave.getTekst() shouldBeEqualTo "Du har en søknad om sykepenger du må fylle ut"
        oppgave.getLink() shouldBeEqualTo "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"
        oppgave.getGrupperingsId() shouldBeEqualTo sykmeldingId

        val brukernotifikasjonDb = brukernotifikasjonRepository.findByIdOrNull(id)!!
        brukernotifikasjonDb.grupperingsid shouldBeEqualTo sykmeldingId
        brukernotifikasjonDb.soknadsid shouldBeEqualTo id
        brukernotifikasjonDb.fnr shouldBeEqualTo fnr
        brukernotifikasjonDb.oppgaveSendt.shouldNotBeNull()
        brukernotifikasjonDb.doneSendt.shouldBeNull()
    }

    @Test
    fun `SENDT søknad fra periode uten database får done oppgave selv uten innslag i db`() {
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

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString()
            )
        )

        val dones = doneKafkaConsumer.ventPåRecords(antall = 1)
        oppgaveKafkaConsumer.ventPåRecords(antall = 0)
        dones.shouldHaveSize(1)

        val nokkel = dones[0].key()
        nokkel.getEventId() shouldBeEqualTo id
        nokkel.getSystembruker() shouldBeEqualTo systembruker

        val done = dones[0].value()
        done.getFodselsnummer() shouldBeEqualTo fnr
        done.getGrupperingsId() shouldBeEqualTo sykmeldingId

        brukernotifikasjonRepository.findByIdOrNull(id).shouldBeNull()
    }

    @Test
    fun `SENDT søknad uten innslag i db får ikke done oppgave`() {
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

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString()
            )
        )

        oppgaveKafkaConsumer.ventPåRecords(antall = 0)
        doneKafkaConsumer.ventPåRecords(antall = 0)
    }

    @Test
    fun `NY og SENDT søknad mottas fra kafka topic og dittnav oppgave og done melding sendes ut`() {

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

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString()
            )
        )

        // Send samme søknad
        val sendtSoknad = enkelSoknad.copy(status = Soknadsstatus.SENDT)
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString()
            )
        )
        // Håndterer duplikat av sendt
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString()
            )
        )

        val oppgaver = oppgaveKafkaConsumer.ventPåRecords(antall = 1)
        val dones = doneKafkaConsumer.ventPåRecords(antall = 1)

        oppgaver.shouldHaveSize(1)
        dones.shouldHaveSize(1)

        val oppgaveNokkel = oppgaver.first().key()
        val oppgave = oppgaver.first().value()
        oppgaveNokkel.getEventId() shouldBeEqualTo id
        oppgaveNokkel.getSystembruker() shouldBeEqualTo systembruker

        oppgave.getFodselsnummer() shouldBeEqualTo fnr
        oppgave.getSikkerhetsnivaa() shouldBeEqualTo 4
        oppgave.getTekst() shouldBeEqualTo "Du har en søknad om sykepenger du må fylle ut"
        oppgave.getLink() shouldBeEqualTo "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"
        oppgave.getGrupperingsId() shouldBeEqualTo sykmeldingId

        val doneNokkel = dones.first().key()
        val done = dones.first().value()

        doneNokkel.getEventId() shouldBeEqualTo id
        doneNokkel.getSystembruker() shouldBeEqualTo systembruker
        done.getFodselsnummer() shouldBeEqualTo fnr
        done.getGrupperingsId() shouldBeEqualTo sykmeldingId
    }

    @Test
    fun `NY og SENDT reisetilskudd mottas fra kafka topic og dittnav oppgave og done melding sendes ut`() {

        val id = UUID.randomUUID().toString()
        val sykmeldingId = UUID.randomUUID().toString()
        val enkelSoknad = EnkelSykepengesoknad(
            id = id,
            status = Soknadsstatus.NY,
            type = Soknadstype.REISETILSKUDD,
            opprettet = etterDatabaseIProd,
            fnr = fnr,
            sykmeldingId = sykmeldingId
        )

        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                enkelSoknad.serialisertTilString()
            )
        )

        // Send samme søknad

        val sendtSoknad = enkelSoknad.copy(status = Soknadsstatus.SENDT)
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString()
            )
        )
        // Håndterer duplikat av sendt
        aivenKafkaProducer.send(
            ProducerRecord(
                SYKEPENGESOKNAD_TOPIC,
                null,
                id,
                sendtSoknad.serialisertTilString()
            )
        )

        val oppgaver = oppgaveKafkaConsumer.ventPåRecords(antall = 1)
        val dones = doneKafkaConsumer.ventPåRecords(antall = 1)

        oppgaver.shouldHaveSize(1)
        dones.shouldHaveSize(1)

        val oppgaveNokkel = oppgaver.first().key()
        val oppgave = oppgaver.first().value()

        oppgaveNokkel.getEventId() shouldBeEqualTo id
        oppgaveNokkel.getSystembruker() shouldBeEqualTo systembruker

        oppgave.getFodselsnummer() shouldBeEqualTo fnr
        oppgave.getSikkerhetsnivaa() shouldBeEqualTo 4
        oppgave.getTekst() shouldBeEqualTo "Du har en søknad om reisetilskudd du må fylle ut"
        oppgave.getLink() shouldBeEqualTo "https://tjenester-q1.nav.no/sykepengesoknad/soknader/$id"
        oppgave.getGrupperingsId() shouldBeEqualTo sykmeldingId

        val doneNokkel = dones.first().key()
        val done = dones.first().value()

        doneNokkel.getEventId() shouldBeEqualTo id
        doneNokkel.getSystembruker() shouldBeEqualTo systembruker
        done.getFodselsnummer() shouldBeEqualTo fnr
        done.getGrupperingsId() shouldBeEqualTo sykmeldingId
    }
}
