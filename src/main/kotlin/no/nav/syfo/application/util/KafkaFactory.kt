package no.nav.syfo.application.util

import io.confluent.kafka.serializers.KafkaAvroSerializer
import java.util.* // ktlint-disable no-wildcard-imports
import no.nav.brukernotifikasjon.schemas.Beskjed
import no.nav.brukernotifikasjon.schemas.Nokkel
import no.nav.syfo.brukernotifkasjon.BrukernotifikasjonKafkaProducer
import no.nav.syfo.kafka.toProducerConfig
import org.apache.kafka.clients.producer.KafkaProducer

class KafkaFactory private constructor() {
    companion object {

        fun getBrukernotifikasjonKafkaProducer(kafkaBaseConfig: Properties): BrukernotifikasjonKafkaProducer {
            val kafkaBrukernotifikasjonProducerConfig = kafkaBaseConfig.toProducerConfig(
                "syfosoknadbrukernotifikasjon", valueSerializer = KafkaAvroSerializer::class, keySerializer = KafkaAvroSerializer::class
            )

            val kafkaproducerBedskjed = KafkaProducer<Nokkel, Beskjed>(kafkaBrukernotifikasjonProducerConfig)
            return BrukernotifikasjonKafkaProducer(
                kafkaproducerBedskjed = kafkaproducerBedskjed
            )
        }
    }
}
