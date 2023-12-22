package no.nav.helse.flex.kafka

import no.nav.brukernotifikasjon.schemas.input.DoneInput
import no.nav.brukernotifikasjon.schemas.input.NokkelInput
import no.nav.brukernotifikasjon.schemas.input.OppgaveInput
import no.nav.helse.flex.logger
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.stereotype.Component

@Component
class BrukernotifikasjonKafkaProdusent(
    private val kafkaproducerOppgave: Producer<NokkelInput, OppgaveInput>,
    private val kafkaproducerDone: Producer<NokkelInput, DoneInput>,
) {
    val log = logger()

    fun opprettBrukernotifikasjonOppgave(
        nokkel: NokkelInput,
        oppgave: OppgaveInput,
    ) {
        try {
            kafkaproducerOppgave.send(ProducerRecord(OPPGAVE_TOPIC, nokkel, oppgave)).get()
        } catch (e: Exception) {
            log.error("Noe gikk galt ved sending av oppgave med id ${nokkel.getEventId()}", e)
            throw e
        }
    }

    fun sendDonemelding(
        nokkel: NokkelInput,
        done: DoneInput,
    ) {
        try {
            kafkaproducerDone.send(ProducerRecord(DONE_TOPIC, nokkel, done)).get()
        } catch (e: Exception) {
            log.error("Noe gikk galt ved ferdigstilling av oppgave med id ${nokkel.getEventId()}", e)
            throw e
        }
    }
}

const val OPPGAVE_TOPIC = "min-side.aapen-brukernotifikasjon-oppgave-v1"
const val DONE_TOPIC = "min-side.aapen-brukernotifikasjon-done-v1"
