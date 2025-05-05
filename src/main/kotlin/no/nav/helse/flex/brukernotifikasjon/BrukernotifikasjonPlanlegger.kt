package no.nav.helse.flex.brukernotifikasjon

import no.nav.helse.flex.domene.EnkelSykepengesoknad
import no.nav.helse.flex.domene.Soknadsstatus
import no.nav.helse.flex.domene.Soknadstype
import no.nav.helse.flex.domene.tilEnkelSykepengesoknad
import no.nav.helse.flex.kafka.nyttVarselTopic
import no.nav.helse.flex.logger
import no.nav.helse.flex.util.minuttMellom0og59
import no.nav.helse.flex.util.osloZone
import no.nav.helse.flex.util.timeMellom9og15
import no.nav.tms.varsel.builder.VarselActionBuilder
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.*

@Component
class BrukernotifikasjonPlanlegger(
    private val kafkaProducer: KafkaProducer<String, String>,
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
                val utsendelsestidspunkt =
                    when (sykepengesoknad.type) {
                        Soknadstype.OPPHOLD_UTLAND -> finnUtsendelsestidspunkt(ZonedDateTime.now(osloZone).plusDays(1))
                        else -> finnUtsendelsestidspunkt()
                    }

                brukernotifikasjonRepository.insert(
                    soknadsid = sykepengesoknad.id,
                    grupperingsid = grupperingsid,
                    fnr = fnr,
                    utsendelsestidspunkt = utsendelsestidspunkt.toInstant(),
                    eksterntVarsel = true,
                    soknadstype = sykepengesoknad.type,
                )
                log.info(
                    "Lagrer ditt nav oppgave med id ${sykepengesoknad.id}, utsendelsestidspunkt $utsendelsestidspunkt, " +
                        "eksternt varsel: true",
                )
            } else {
                log.info("Har allerede prossesert søknad med id  ${sykepengesoknad.id}")
            }
        } else if (sykepengesoknad.skalSendeDoneMelding()) {
            val brukernotfikasjon = brukernotifikasjonRepository.findByIdOrNull(sykepengesoknad.id)
            if (brukernotfikasjon != null) {
                when {
                    brukernotfikasjon.utsendelsestidspunkt != null -> {
                        log.info(
                            "Avbryter utsendelse av søknad med id ${sykepengesoknad.id} og utsendelsestidspunkt " +
                                "${brukernotfikasjon.utsendelsestidspunkt}",
                        )
                        brukernotifikasjonRepository.save(brukernotfikasjon.copy(utsendelsestidspunkt = null))
                    }

                    brukernotfikasjon.doneSendt == null -> {
                        log.info("Sender done melding med id ${sykepengesoknad.id} ")

                        val inaktiverVarsel =
                            VarselActionBuilder.inaktiver {
                                varselId = sykepengesoknad.id
                            }

                        kafkaProducer.send(ProducerRecord(nyttVarselTopic, sykepengesoknad.id, inaktiverVarsel)).get()

                        brukernotifikasjonRepository.save(brukernotfikasjon.copy(doneSendt = Instant.now()))
                    }

                    else -> {
                        log.info("Har allerede sendt brukernotifikasjon done melding for søknad med id  ${sykepengesoknad.id}")
                    }
                }
            }
        }
    }
}

internal fun finnUtsendelsestidspunkt(now: ZonedDateTime = ZonedDateTime.now(osloZone)): ZonedDateTime {
    var utsendelsestidspunkt = now.withHour(timeMellom9og15()).withMinute(minuttMellom0og59())

    if (now.hour >= 9) {
        utsendelsestidspunkt = utsendelsestidspunkt.plusDays(1)
    }

    if (utsendelsestidspunkt.dayOfWeek == DayOfWeek.SATURDAY) {
        utsendelsestidspunkt = utsendelsestidspunkt.plusDays(2)
    }

    if (utsendelsestidspunkt.dayOfWeek == DayOfWeek.SUNDAY) {
        utsendelsestidspunkt = utsendelsestidspunkt.plusDays(1)
    }

    return utsendelsestidspunkt
}

private fun EnkelSykepengesoknad.skalOppretteOppgave(): Boolean = this.status == Soknadsstatus.NY

private fun EnkelSykepengesoknad.skalSendeDoneMelding(): Boolean =
    when (this.status) {
        Soknadsstatus.SLETTET -> true
        Soknadsstatus.AVBRUTT -> true
        Soknadsstatus.SENDT -> true
        Soknadsstatus.UTGAATT -> true
        Soknadsstatus.NY -> false
        Soknadsstatus.FREMTIDIG -> false
        Soknadsstatus.KORRIGERT -> false
    }
