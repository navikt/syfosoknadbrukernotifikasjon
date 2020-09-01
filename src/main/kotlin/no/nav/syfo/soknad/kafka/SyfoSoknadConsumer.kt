package no.nav.syfo.soknad.kafka

import java.time.Duration
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer

class SyfoSoknadConsumer(
    private val syfoSoknadConsumer: KafkaConsumer<String, String>
) {
    fun poll(): ConsumerRecords<String, String> {
        return syfoSoknadConsumer.poll(Duration.ofMillis(0))
    }
}
