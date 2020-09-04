package no.nav.syfo.soknad.kafka

import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import java.time.Duration

class SyfoSoknadConsumer(
    private val syfoSoknadConsumer: KafkaConsumer<String, String>
) {
    fun poll(): ConsumerRecords<String, String> {
        return syfoSoknadConsumer.poll(Duration.ofMillis(500))
    }
}
