package no.nav.syfo.soknad.service

import kotlinx.coroutines.delay
import no.nav.brukernotifikasjon.schemas.Beskjed
import no.nav.brukernotifikasjon.schemas.Nokkel
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.brukernotifkasjon.BrukernotifikasjonKafkaProducer
import no.nav.syfo.log
import no.nav.syfo.soknad.kafka.SyfoSoknadConsumer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class SyfoSoknadService(
    private val applicationState: ApplicationState,
    private val syfoSoknadConsumer: SyfoSoknadConsumer,
    private val brukernotifikasjonKafkaProducer: BrukernotifikasjonKafkaProducer,
    private val servicebruker: String
) {
    suspend fun start() {
        while (applicationState.ready) {
            val consumerRecords = syfoSoknadConsumer.poll()
            consumerRecords.forEach {
                val erSoknad = it.headers().any { header ->
                    header.key() == "type" && String(header.value()) == "Soknad"
                }
                it.offset()
                if (erSoknad) {
                    val id = UUID.nameUUIDFromBytes("${it.partition()}-${it.offset()}".toByteArray())
                    handterSyfoSoknad(
                        id = id,
                        fnr = it.key(),
                        soknad = it.value()
                    )
                }
            }
            delay(1)
        }
    }

    fun handterSyfoSoknad(id: UUID, fnr: String, soknad: String) {

        log.info("Legger dittnav beskjed på kafka med id $id")
        brukernotifikasjonKafkaProducer.opprettBrukernotifikasjonMelding(
            Nokkel(servicebruker, id.toString()),
            Beskjed(
                System.currentTimeMillis(),
                Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli(),
                fnr,
                id.toString(),
                "NAV har behandlet søknad om sykepenger",
                "#", // TODO: Hva skal link være her?
                4
            )
        )
    }
}
