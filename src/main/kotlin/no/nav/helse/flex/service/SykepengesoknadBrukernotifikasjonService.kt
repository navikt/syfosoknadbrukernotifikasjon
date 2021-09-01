package no.nav.helse.flex.service

import no.nav.brukernotifikasjon.schemas.builders.DoneBuilder
import no.nav.brukernotifikasjon.schemas.builders.NokkelBuilder
import no.nav.helse.flex.brukernotifkasjon.BrukernotifikasjonKafkaProdusent
import no.nav.helse.flex.db.*
import no.nav.helse.flex.domene.EnkelSykepengesoknad
import no.nav.helse.flex.domene.Soknadsstatus
import no.nav.helse.flex.domene.Soknadstype
import no.nav.helse.flex.domene.tilEnkelSykepengesoknad
import no.nav.helse.flex.logger
import no.nav.helse.flex.util.minuttMellom0og59
import no.nav.helse.flex.util.osloZone
import no.nav.helse.flex.util.timeMellom9og15
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.*

@Component
class SykepengesoknadBrukernotifikasjonService(
    private val brukernotifikasjonKafkaProdusent: BrukernotifikasjonKafkaProdusent,
    @Value("\${on-prem-kafka.username}") val servicebruker: String,
    private val brukernotifikasjonRepository: BrukernotifikasjonRepository,
) {

    val log = logger()

    fun handterSykepengesoknad(sykepengesoknadSomString: String) {

        val sykepengesoknad = sykepengesoknadSomString.tilEnkelSykepengesoknad()

        val fnr = sykepengesoknad.fnr

        val grupperingsid = sykepengesoknad.sykmeldingId ?: sykepengesoknad.id

        if (sykepengesoknad.skalOppretteOppgave()) {
            val brukernotfikasjon = brukernotifikasjonRepository.findByIdOrNull(sykepengesoknad.id)
            if (brukernotfikasjon == null) {

                val eksterntVarsel = sykepengesoknad.skalFåEksterntVarselViaBrukernotifikasjon()
                val utsendelsestidspunkt = if (eksterntVarsel) {
                    finnUtsendelsestidspunktFraNåværendeTidspunkt()
                } else {
                    ZonedDateTime.now(osloZone)
                }

                brukernotifikasjonRepository.insert(
                    soknadsid = sykepengesoknad.id,
                    grupperingsid = grupperingsid,
                    fnr = fnr,
                    utsendelsestidspunkt = utsendelsestidspunkt.toInstant(),
                    eksterntVarsel = eksterntVarsel,
                    soknadstype = sykepengesoknad.type
                )
                log.info("Lagrer ditt nav oppgave med id ${sykepengesoknad.id}, utsendelsestidspunkt $utsendelsestidspunkt, eksternt varsel: $eksterntVarsel")
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
                        val nokkel = NokkelBuilder()
                            .withSystembruker(servicebruker)
                            .withEventId(sykepengesoknad.id)
                            .build()

                        val done = DoneBuilder()
                            .withTidspunkt(LocalDateTime.now(ZoneOffset.UTC))
                            .withGrupperingsId(grupperingsid)
                            .withFodselsnummer(fnr)
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

private fun EnkelSykepengesoknad.skalFåEksterntVarselViaBrukernotifikasjon(): Boolean {
    return this.opprettet.isAfter(LocalDate.of(2021, 9, 2).atTime(11, 0))
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
