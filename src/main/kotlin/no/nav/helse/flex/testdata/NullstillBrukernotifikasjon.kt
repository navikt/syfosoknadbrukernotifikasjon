package no.nav.helse.flex.testdata

import no.nav.brukernotifikasjon.schemas.builders.DoneInputBuilder
import no.nav.brukernotifikasjon.schemas.builders.NokkelInputBuilder
import no.nav.helse.flex.brukernotifikasjon.BrukernotifikasjonRepository
import no.nav.helse.flex.kafka.BrukernotifikasjonKafkaProdusent
import no.nav.helse.flex.logger
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
@Profile("testdatareset")
class NullstillBrukernotifikasjon(
    private val brukernotifikasjonRepository: BrukernotifikasjonRepository,
    private val brukernotifikasjonKafkaProdusent: BrukernotifikasjonKafkaProdusent,
) {
    private val log = logger()

    fun nullstill(fnr: String): Int {
        var antall = 0

        brukernotifikasjonRepository
            .findByFnr(fnr)
            .forEach {
                if (it.oppgaveSendt != null && it.doneSendt == null) {
                    log.info("Sender done melding med id ${it.soknadsid} og grupperingsid ${it.grupperingsid}")

                    val nokkel = NokkelInputBuilder()
                        .withEventId(it.soknadsid)
                        .withGrupperingsId(it.grupperingsid)
                        .withFodselsnummer(fnr)
                        .withNamespace("flex")
                        .withAppnavn("syfosoknadbrukernotifikasjon")
                        .build()

                    val done = DoneInputBuilder()
                        .withTidspunkt(LocalDateTime.now(ZoneOffset.UTC))
                        .build()

                    brukernotifikasjonKafkaProdusent.sendDonemelding(nokkel, done)
                }

                brukernotifikasjonRepository.deleteById(it.soknadsid)
                antall++
            }

        return antall
    }
}
