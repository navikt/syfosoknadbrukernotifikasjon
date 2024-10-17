package no.nav.helse.flex.brukernotifikasjon

import no.nav.helse.flex.domene.Brukernotifikasjon
import no.nav.helse.flex.domene.Soknadstype
import no.nav.helse.flex.kafka.nyttVarselTopic
import no.nav.helse.flex.logger
import no.nav.tms.varsel.action.*
import no.nav.tms.varsel.builder.VarselActionBuilder
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class BrukernotifikasjonOpprettelse(
    private val kafkaProducer: KafkaProducer<String, String>,
    @Value("\${frontend-url}") val sykepengesoknadFrontend: String,
    private val brukernotifikasjonRepository: BrukernotifikasjonRepository,
) {
    val log = logger()

    fun opprettBrukernotifikasjoner(now: Instant = Instant.now()): Int {
        var antall = 0
        val brukernotifikasjoner =
            brukernotifikasjonRepository.findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(now)

        log.info("Fant ${brukernotifikasjoner.size} brukernotifikasjoner ")

        brukernotifikasjoner.forEach {
            val brukernotifikasjon = brukernotifikasjonRepository.findByIdOrNull(it.soknadsid)!!
            if (brukernotifikasjon.utsendelsestidspunkt != null && brukernotifikasjon.utsendelsestidspunkt.isBefore(now)) {
                val opprettVarsel = brukernotifikasjon.tilOpprettVarsel(sykepengesoknadFrontend)

                log.info(
                    "Sender dittnav varsel med id ${brukernotifikasjon.soknadsid}",
                )

                kafkaProducer.send(ProducerRecord(nyttVarselTopic, brukernotifikasjon.soknadsid, opprettVarsel)).get()

                brukernotifikasjonRepository.save(
                    brukernotifikasjon.copy(
                        oppgaveSendt = Instant.now(),
                        utsendelsestidspunkt = null,
                    ),
                )
                antall++
            }
        }
        return antall
    }
}

private fun Brukernotifikasjon.opprettBrukernotifikasjonTekst(): String =
    when (this.soknadstype) {
        Soknadstype.SELVSTENDIGE_OG_FRILANSERE,
        Soknadstype.ARBEIDSTAKERE,
        Soknadstype.ANNET_ARBEIDSFORHOLD,
        Soknadstype.ARBEIDSLEDIG,
        Soknadstype.BEHANDLINGSDAGER,
        -> "Du har en søknad om sykepenger du må fylle ut"

        Soknadstype.REISETILSKUDD -> "Du har en søknad om reisetilskudd du må fylle ut"
        Soknadstype.GRADERT_REISETILSKUDD -> "Du har en søknad om sykepenger med reisetilskudd du må fylle ut"
        Soknadstype.OPPHOLD_UTLAND -> "Du har en søknad om å beholde sykepengene for reise utenfor EU/EØS du må fylle ut"
        else -> throw IllegalArgumentException(
            "Søknad ${this.soknadsid} er av type ${this.soknadstype} og skal ikke ha brukernotifikasjon oppgave",
        )
    }

fun Brukernotifikasjon.tilOpprettVarsel(sykepengesoknadFrontend: String): String {
    val brukernotifikasjon = this
    val soknadLenke =
        if (brukernotifikasjon.soknadstype == Soknadstype.OPPHOLD_UTLAND) {
            "${sykepengesoknadFrontend.substringBefore("soknader/")}sykepengesoknad-utland"
        } else {
            "$sykepengesoknadFrontend${brukernotifikasjon.soknadsid}"
        }

    return VarselActionBuilder.opprett {
        type = Varseltype.Oppgave
        varselId = brukernotifikasjon.soknadsid
        sensitivitet = Sensitivitet.High
        ident = brukernotifikasjon.fnr
        tekst =
            Tekst(
                spraakkode = "nb",
                tekst = brukernotifikasjon.opprettBrukernotifikasjonTekst(),
                default = true,
            )
        aktivFremTil = null
        link = soknadLenke
        eksternVarsling =
            if (brukernotifikasjon.eksterntVarsel) {
                EksternVarslingBestilling(
                    prefererteKanaler = listOf(EksternKanal.SMS),
                )
            } else {
                null
            }
    }
}
