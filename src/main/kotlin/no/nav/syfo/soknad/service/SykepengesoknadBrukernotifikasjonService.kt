package no.nav.syfo.soknad.service

import kotlinx.coroutines.delay
import no.nav.brukernotifikasjon.schemas.Done
import no.nav.brukernotifikasjon.schemas.Nokkel
import no.nav.brukernotifikasjon.schemas.Oppgave
import no.nav.syfo.Environment
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.brukernotifkasjon.BrukernotifikasjonKafkaProducer
import no.nav.syfo.log
import no.nav.syfo.soknad.domene.EnkelSykepengesoknad
import no.nav.syfo.soknad.domene.Soknadsstatus
import no.nav.syfo.soknad.domene.Soknadstype
import no.nav.syfo.soknad.domene.tilEnkelSykepengesoknad
import no.nav.syfo.soknad.kafka.SyfosoknadKafkaPoller
import java.time.LocalDate

class SykepengesoknadBrukernotifikasjonService(
    private val applicationState: ApplicationState,
    private val syfosoknadKafkaPoller: SyfosoknadKafkaPoller,
    private val brukernotifikasjonKafkaProducer: BrukernotifikasjonKafkaProducer,
    private val servicebruker: String,
    private val environment: Environment
) {
    suspend fun start() {
        while (applicationState.ready) {
            val consumerRecords = syfosoknadKafkaPoller.poll()
            consumerRecords.forEach {

                val sykepengesoknad = it.value().tilEnkelSykepengesoknad()
                handterSykepengesoknad(sykepengesoknad)
            }
            delay(1)
        }
    }

    fun handterSykepengesoknad(sykepengesoknad: EnkelSykepengesoknad) {
        val fnr = sykepengesoknad.fnr
        if (fnr == null) {
            log.info("Sykepengesoknad ${sykepengesoknad.id} har ikke fnr, behandler ikke brukernotifikasjon")
            return
        }
        if (sykepengesoknad.erNyNokForBrukernotifikasjon()) {
            val grupperingsid = sykepengesoknad.sykmeldingId ?: sykepengesoknad.id

            if (sykepengesoknad.skalOppretteOppgave()) {
                log.info("Sender dittnav oppgave med id ${sykepengesoknad.id} og grupperingsid $grupperingsid")
                brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonOppgave(
                    Nokkel(servicebruker, sykepengesoknad.id),
                    Oppgave(
                        System.currentTimeMillis(),
                        fnr,
                        grupperingsid,
                        "Du har en søknad om sykepenger du må fylle ut",
                        "${environment.sykepengesoknadFrontend}${sykepengesoknad.id}",
                        4
                    )
                )
            }
            if (sykepengesoknad.skalSendeDoneMelding()) {
                log.info("Sender done melding med id ${sykepengesoknad.id} og grupperingsid $grupperingsid")
                brukernotifikasjonKafkaProducer.sendDonemelding(
                    Nokkel(servicebruker, sykepengesoknad.id),
                    Done(
                        System.currentTimeMillis(),
                        fnr,
                        grupperingsid
                    )
                )
            }
        } else {
            log.info("Sykepengesøknad ${sykepengesoknad.id} er ikke ny nok for å få brukernotifikasjon / done event")
        }
    }

    private fun EnkelSykepengesoknad.erNyNokForBrukernotifikasjon(): Boolean {
        return if (environment.isProd()) {
            this.opprettet.isAfter(LocalDate.of(2020, 12, 1).atTime(9, 0))
        } else {
            this.opprettet.isAfter(LocalDate.of(2020, 10, 10).atTime(9, 0))
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
            Soknadsstatus.NY -> false
            Soknadsstatus.FREMTIDIG -> false
            Soknadsstatus.KORRIGERT -> false
        }
    }
}
