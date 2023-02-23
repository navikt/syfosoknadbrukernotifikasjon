package no.nav.helse.flex.brukernotifikasjon

import no.nav.brukernotifikasjon.schemas.builders.NokkelInputBuilder
import no.nav.brukernotifikasjon.schemas.builders.OppgaveInputBuilder
import no.nav.brukernotifikasjon.schemas.builders.domain.PreferertKanal
import no.nav.helse.flex.domene.Brukernotifikasjon
import no.nav.helse.flex.domene.Soknadstype
import no.nav.helse.flex.kafka.BrukernotifikasjonKafkaProdusent
import no.nav.helse.flex.logger
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class BrukernotifikasjonOpprettelse(
    private val brukernotifikasjonKafkaProdusent: BrukernotifikasjonKafkaProdusent,
    @Value("\${frontend-url}") val sykepengesoknadFrontend: String,
    private val brukernotifikasjonRepository: BrukernotifikasjonRepository
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
                val oppgave = OppgaveInputBuilder()
                    .withTidspunkt(LocalDateTime.now(ZoneOffset.UTC))
                    .withTekst(brukernotifikasjon.opprettBrukernotifikasjonTekst())
                    .withLink(URL("${sykepengesoknadFrontend}${brukernotifikasjon.soknadsid}"))
                    .withSikkerhetsnivaa(4)
                    .withEksternVarsling(brukernotifikasjon.eksterntVarsel)
                    .also { builder ->
                        if (brukernotifikasjon.eksterntVarsel) {
                            builder.withPrefererteKanaler(PreferertKanal.SMS)
                        } else {
                            builder.withPrefererteKanaler()
                        }
                    }
                    .build()

                val nokkel = NokkelInputBuilder()
                    .withEventId(brukernotifikasjon.soknadsid)
                    .withGrupperingsId(brukernotifikasjon.grupperingsid)
                    .withFodselsnummer(brukernotifikasjon.fnr)
                    .withNamespace("flex")
                    .withAppnavn("syfosoknadbrukernotifikasjon")
                    .build()

                log.info("Sender dittnav oppgave med id ${brukernotifikasjon.soknadsid} og grupperingsid ${brukernotifikasjon.grupperingsid} og eksternt varsel ${oppgave.getEksternVarsling()}")

                brukernotifikasjonKafkaProdusent.opprettBrukernotifikasjonOppgave(nokkel, oppgave)
                brukernotifikasjonRepository.save(
                    brukernotifikasjon.copy(
                        oppgaveSendt = Instant.now(),
                        utsendelsestidspunkt = null
                    )
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
        Soknadstype.BEHANDLINGSDAGER -> "Du har en søknad om sykepenger du må fylle ut"
        Soknadstype.REISETILSKUDD -> "Du har en søknad om reisetilskudd du må fylle ut"
        Soknadstype.GRADERT_REISETILSKUDD -> "Du har en søknad om sykepenger med reisetilskudd du må fylle ut"
        else -> throw IllegalArgumentException("Søknad ${this.soknadsid} er av type ${this.soknadstype} og skal ikke ha brukernotifikasjon oppgave")
    }
