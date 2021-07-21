package no.nav.helse.flex.service

import no.nav.brukernotifikasjon.schemas.Done
import no.nav.brukernotifikasjon.schemas.Nokkel
import no.nav.brukernotifikasjon.schemas.Oppgave
import no.nav.helse.flex.brukernotifkasjon.BrukernotifikasjonKafkaProdusent
import no.nav.helse.flex.db.*
import no.nav.helse.flex.domene.EnkelSykepengesoknad
import no.nav.helse.flex.domene.Soknadsstatus
import no.nav.helse.flex.domene.Soknadstype
import no.nav.helse.flex.domene.tilEnkelSykepengesoknad
import no.nav.helse.flex.logger
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

@Component
class SykepengesoknadBrukernotifikasjonService(
    private val brukernotifikasjonKafkaProdusent: BrukernotifikasjonKafkaProdusent,
    @Value("\${on-prem-kafka.username}") val servicebruker: String,
    @Value("\${frontend-url}") val sykepengesoknadFrontend: String,
    private val brukernotifikasjonRepository: BrukernotifikasjonRepository,
) {

    val log = logger()

    fun handterSykepengesoknad(sykepengesoknadSomString: String) {

        val sykepengesoknad = sykepengesoknadSomString.tilEnkelSykepengesoknad()

        val fnr = sykepengesoknad.fnr
        if (fnr == null) {
            log.info("Sykepengesoknad ${sykepengesoknad.id} har ikke fnr, behandler ikke brukernotifikasjon")
            return
        }

        val grupperingsid = sykepengesoknad.sykmeldingId ?: sykepengesoknad.id

        if (sykepengesoknad.skalOppretteOppgave()) {
            val brukernotfikasjon = brukernotifikasjonRepository.findByIdOrNull(sykepengesoknad.id)
            if (brukernotfikasjon == null) {
                log.info("Sender dittnav oppgave med id ${sykepengesoknad.id} og grupperingsid $grupperingsid")
                brukernotifikasjonKafkaProdusent.opprettBrukernotifikasjonOppgave(
                    Nokkel(servicebruker, sykepengesoknad.id),
                    Oppgave(
                        System.currentTimeMillis(),
                        fnr,
                        grupperingsid,
                        sykepengesoknad.opprettBrukernotifikasjonTekst(),
                        "${sykepengesoknadFrontend}${sykepengesoknad.id}",
                        4
                    )
                )
                brukernotifikasjonRepository.insert(
                    soknadsid = sykepengesoknad.id,
                    grupperingsid = grupperingsid,
                    fnr = fnr,
                    oppgaveSendt = Instant.now()
                )
            } else {
                log.info("Har allerede sendt brukernotifikasjon oppgave for søknad med id  ${sykepengesoknad.id}")
            }
        } else if (sykepengesoknad.skalSendeDoneMelding()) {
            val brukernotfikasjon = brukernotifikasjonRepository.findByIdOrNull(sykepengesoknad.id)
            if (brukernotfikasjon != null) {
                if (brukernotfikasjon.doneSendt == null) {
                    log.info("Sender done melding med id ${sykepengesoknad.id} og grupperingsid $grupperingsid")
                    brukernotifikasjonKafkaProdusent.sendDonemelding(
                        Nokkel(servicebruker, sykepengesoknad.id),
                        Done(
                            System.currentTimeMillis(),
                            fnr,
                            grupperingsid
                        )
                    )
                    brukernotifikasjonRepository.save(brukernotfikasjon.copy(doneSendt = Instant.now()))
                } else {
                    log.info("Har allerede sendt brukernotifikasjon done melding for søknad med id  ${sykepengesoknad.id}")
                }
            } else {
                // Sender done meldinger slik at de vi har i produksjon i dag kan donnes ut
                if (sykepengesoknad.kanFåDonemeldingUtenAtBrukernotfifikasjonErIDatabasen()) {
                    log.info("Sender done melding med id ${sykepengesoknad.id} og grupperingsid $grupperingsid på brukernotifikasjon vi nok har sendt tidligere")
                    brukernotifikasjonKafkaProdusent.sendDonemelding(
                        Nokkel(servicebruker, sykepengesoknad.id),
                        Done(
                            System.currentTimeMillis(),
                            fnr,
                            grupperingsid
                        )
                    )
                }
            }
        }
    }

    private fun EnkelSykepengesoknad.kanFåDonemeldingUtenAtBrukernotfifikasjonErIDatabasen(): Boolean {

        return this.opprettet.isAfter(LocalDate.of(2020, 10, 30).atTime(11, 0)) &&
            this.opprettet.isBefore(LocalDate.of(2020, 11, 4).atTime(0, 0))
    }

    private fun EnkelSykepengesoknad.skalOppretteOppgave(): Boolean {
        return this.status == Soknadsstatus.NY && this.type != Soknadstype.OPPHOLD_UTLAND
    }

    private fun EnkelSykepengesoknad.skalSendeDoneMelding(): Boolean {
        if (this.type == Soknadstype.OPPHOLD_UTLAND) {
            return false
        }
        return when (this.status) {
            Soknadsstatus.SLETTET -> true
            Soknadsstatus.AVBRUTT -> true
            Soknadsstatus.SENDT -> true
            Soknadsstatus.NY -> false
            Soknadsstatus.FREMTIDIG -> false
            Soknadsstatus.KORRIGERT -> false
        }
    }

    private fun EnkelSykepengesoknad.opprettBrukernotifikasjonTekst() =
        when (this.type) {
            Soknadstype.SELVSTENDIGE_OG_FRILANSERE,
            Soknadstype.ARBEIDSTAKERE,
            Soknadstype.ANNET_ARBEIDSFORHOLD,
            Soknadstype.ARBEIDSLEDIG,
            Soknadstype.BEHANDLINGSDAGER -> "Du har en søknad om sykepenger du må fylle ut"
            Soknadstype.REISETILSKUDD -> "Du har en søknad om reisetilskudd du må fylle ut"
            Soknadstype.GRADERT_REISETILSKUDD -> "Du har en søknad om sykepenger med reisetilskudd du må fylle ut"
            else -> throw IllegalArgumentException("Søknad ${this.id} er av type ${this.type} og skal ikke ha brukernotifikasjon oppgave")
        }
}
