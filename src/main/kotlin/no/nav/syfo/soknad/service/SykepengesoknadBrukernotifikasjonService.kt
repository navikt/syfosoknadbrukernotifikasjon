package no.nav.syfo.soknad.service

import kotlinx.coroutines.delay
import no.nav.brukernotifikasjon.schemas.Done
import no.nav.brukernotifikasjon.schemas.Nokkel
import no.nav.brukernotifikasjon.schemas.Oppgave
import no.nav.syfo.Environment
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.brukernotifkasjon.BrukernotifikasjonKafkaProducer
import no.nav.syfo.db.DatabaseInterface
import no.nav.syfo.log
import no.nav.syfo.soknad.db.finnBrukernotifikasjon
import no.nav.syfo.soknad.db.opprettBrukernotifikasjon
import no.nav.syfo.soknad.db.settDoneSendt
import no.nav.syfo.soknad.domene.EnkelSykepengesoknad
import no.nav.syfo.soknad.domene.Soknadsstatus
import no.nav.syfo.soknad.domene.Soknadstype
import no.nav.syfo.soknad.domene.tilEnkelSykepengesoknad
import no.nav.syfo.soknad.kafka.SyfosoknadKafkaPoller
import java.time.Instant
import java.time.LocalDate

class SykepengesoknadBrukernotifikasjonService(
    private val applicationState: ApplicationState,
    private val syfosoknadKafkaPoller: SyfosoknadKafkaPoller,
    private val brukernotifikasjonKafkaProducer: BrukernotifikasjonKafkaProducer,
    private val servicebruker: String,
    private val database: DatabaseInterface,
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

        val grupperingsid = sykepengesoknad.sykmeldingId ?: sykepengesoknad.id

        if (sykepengesoknad.skalOppretteOppgave()) {
            val brukernotfikasjon = database.finnBrukernotifikasjon(sykepengesoknad.id)
            if (brukernotfikasjon == null) {
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
                database.opprettBrukernotifikasjon(
                    soknadsid = sykepengesoknad.id,
                    grupperingsid = grupperingsid,
                    fnr = fnr,
                    oppgaveSendt = Instant.now()
                )
            } else {
                log.info("Har allerede sendt brukernotifikasjon oppgave for søknad med id  ${sykepengesoknad.id}")
            }
        } else if (sykepengesoknad.skalSendeDoneMelding()) {
            val brukernotfikasjon = database.finnBrukernotifikasjon(sykepengesoknad.id)
            if (brukernotfikasjon != null) {
                if (brukernotfikasjon.doneSendt == null) {
                    log.info("Sender done melding med id ${sykepengesoknad.id} og grupperingsid $grupperingsid")
                    brukernotifikasjonKafkaProducer.sendDonemelding(
                        Nokkel(servicebruker, sykepengesoknad.id),
                        Done(
                            System.currentTimeMillis(),
                            fnr,
                            grupperingsid
                        )
                    )
                    database.settDoneSendt(sykepengesoknad.id)
                } else {
                    log.info("Har allerede sendt brukernotifikasjon done melding for søknad med id  ${sykepengesoknad.id}")
                }
            } else {
                // Sender done meldinger slik at de vi har i produksjon i dag kan donnes ut
                if (sykepengesoknad.kanFåDonemeldingUtenAtBrukernotfifikasjonErIDatabasen()) {
                    log.info("Sender done melding med id ${sykepengesoknad.id} og grupperingsid $grupperingsid på brukernotifikasjon vi nok har sendt tidligere")
                    brukernotifikasjonKafkaProducer.sendDonemelding(
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

    companion object EnkelSykepengesoknadUtil {
        fun EnkelSykepengesoknad.kanFåDonemeldingUtenAtBrukernotfifikasjonErIDatabasen(): Boolean {

            return this.opprettet.isAfter(LocalDate.of(2020, 10, 30).atTime(11, 0)) &&
                this.opprettet.isBefore(LocalDate.of(2020, 11, 4).atTime(0, 0))
        }

        fun EnkelSykepengesoknad.skalOppretteOppgave(): Boolean {
            return this.status == Soknadsstatus.NY && this.type != Soknadstype.OPPHOLD_UTLAND
        }

        fun EnkelSykepengesoknad.skalSendeDoneMelding(): Boolean {
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
}
