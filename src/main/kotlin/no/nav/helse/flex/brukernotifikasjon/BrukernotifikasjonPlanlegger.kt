package no.nav.helse.flex.brukernotifikasjon

import no.nav.brukernotifikasjon.schemas.builders.DoneInputBuilder
import no.nav.brukernotifikasjon.schemas.builders.NokkelInputBuilder
import no.nav.helse.flex.domene.EnkelSykepengesoknad
import no.nav.helse.flex.domene.Soknadsstatus
import no.nav.helse.flex.domene.Soknadstype
import no.nav.helse.flex.domene.tilEnkelSykepengesoknad
import no.nav.helse.flex.kafka.BrukernotifikasjonKafkaProdusent
import no.nav.helse.flex.logger
import no.nav.helse.flex.util.minuttMellom0og59
import no.nav.helse.flex.util.osloZone
import no.nav.helse.flex.util.timeMellom9og15
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.*

@Component
class BrukernotifikasjonPlanlegger(
    private val brukernotifikasjonKafkaProdusent: BrukernotifikasjonKafkaProdusent,
    private val brukernotifikasjonRepository: BrukernotifikasjonRepository,
) {

    val log = logger()

    fun planleggBrukernotfikasjon(sykepengesoknadSomString: String) {

        val sykepengesoknad = sykepengesoknadSomString.tilEnkelSykepengesoknad()

        val fnr = sykepengesoknad.fnr

        val grupperingsid = sykepengesoknad.sykmeldingId ?: sykepengesoknad.id

        if (sykepengesoknad.skalOppretteOppgave()) {
            val brukernotfikasjon = brukernotifikasjonRepository.findByIdOrNull(sykepengesoknad.id)
            if (brukernotfikasjon == null) {

                val utsendelsestidspunkt = finnUtsendelsestidspunktFraNåværendeTidspunkt()

                brukernotifikasjonRepository.insert(
                    soknadsid = sykepengesoknad.id,
                    grupperingsid = grupperingsid,
                    fnr = fnr,
                    utsendelsestidspunkt = utsendelsestidspunkt.toInstant(),
                    eksterntVarsel = true,
                    soknadstype = sykepengesoknad.type
                )
                log.info("Lagrer ditt nav oppgave med id ${sykepengesoknad.id}, utsendelsestidspunkt $utsendelsestidspunkt, eksternt varsel: true")
            } else {
                log.info("Har allerede prossesert søknad med id  ${sykepengesoknad.id}")
            }
        } else if (sykepengesoknad.skalSendeDoneMelding()) {
            val brukernotfikasjon = brukernotifikasjonRepository.findByIdOrNull(sykepengesoknad.id)
            if (brukernotfikasjon != null) {
                when {
                    brukernotfikasjon.utsendelsestidspunkt != null -> {
                        log.info("Avbryter utsendelse av søknad med id ${sykepengesoknad.id} og utsendelsestidspunkt ${brukernotfikasjon.utsendelsestidspunkt}")
                        brukernotifikasjonRepository.save(brukernotfikasjon.copy(utsendelsestidspunkt = null))
                    }
                    brukernotfikasjon.doneSendt == null -> {
                        log.info("Sender done melding med id ${sykepengesoknad.id} og grupperingsid $grupperingsid")
                        val nokkel = NokkelInputBuilder()
                            .withEventId(sykepengesoknad.id)
                            .withGrupperingsId(grupperingsid)
                            .withFodselsnummer(fnr)
                            .withNamespace("flex")
                            .withAppnavn("syfosoknadbrukernotifikasjon")
                            .build()

                        val done = DoneInputBuilder()
                            .withTidspunkt(LocalDateTime.now(ZoneOffset.UTC))
                            .build()

                        brukernotifikasjonKafkaProdusent.sendDonemelding(nokkel, done)
                        brukernotifikasjonRepository.save(brukernotfikasjon.copy(doneSendt = Instant.now()))
                    }
                    else -> {
                        log.info("Har allerede sendt brukernotifikasjon done melding for søknad med id  ${sykepengesoknad.id}")
                    }
                }
            }
        }
    }

    private fun finnUtsendelsestidspunktFraNåværendeTidspunkt(): ZonedDateTime {

        val påDagtid = LocalTime.of(timeMellom9og15(), minuttMellom0og59())
        return if (ZonedDateTime.now(osloZone).hour >= 9) {
            ZonedDateTime.of(LocalDate.now().plusDays(1), påDagtid, osloZone)
        } else {
            ZonedDateTime.of(LocalDate.now(), påDagtid, osloZone)
        }
    }
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
        Soknadsstatus.UTGAATT -> true
        Soknadsstatus.NY -> false
        Soknadsstatus.FREMTIDIG -> false
        Soknadsstatus.KORRIGERT -> false
    }
}
