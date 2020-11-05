package no.nav.syfo.application.util

import no.nav.syfo.Environment
import no.nav.syfo.application.Topics.SYFO_SOKNAD_V2
import no.nav.syfo.application.Topics.SYFO_SOKNAD_V3
import no.nav.syfo.kafka.envOverrides
import no.nav.syfo.kafka.loadBaseConfig
import no.nav.syfo.kafka.toConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer

class KafkaClients(env: Environment) {

    val syfoSoknadConsumer = syfoSoknadConsumer(env)

    private fun syfoSoknadConsumer(env: Environment): KafkaConsumer<String, String> {

        val config = loadBaseConfig(env, env.hentKafkaCredentials()).envOverrides()
        config["auto.offset.reset"] = "none"

        val properties = config.toConsumerConfig("${env.applicationName}-consumer", StringDeserializer::class)
        properties.let { it[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = "1" }

        val consumer = KafkaConsumer<String, String>(properties)
        consumer.subscribe(listOf(SYFO_SOKNAD_V2, SYFO_SOKNAD_V3))

        return consumer
    }
}
