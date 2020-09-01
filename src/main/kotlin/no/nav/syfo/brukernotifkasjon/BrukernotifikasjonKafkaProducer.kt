package no.nav.syfo.brukernotifkasjon

import no.nav.brukernotifikasjon.schemas.Beskjed
import no.nav.brukernotifikasjon.schemas.Nokkel
import no.nav.syfo.log
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

class BrukernotifikasjonKafkaProducer(
    private val kafkaproducerBedskjed: KafkaProducer<Nokkel, Beskjed>
) {
    fun opprettBrukernotifikasjonMelding(nokkel: Nokkel, beskjed: Beskjed) {
        try {
            kafkaproducerBedskjed.send(ProducerRecord("aapen-brukernotifikasjon-nyMelding-v1", nokkel, beskjed)).get()
        } catch (e: Exception) {
            log.error("Noe gikk galt ved sending av melding med id {}: ${e.message}", nokkel.getEventId())
            throw e
        }
    }
}
