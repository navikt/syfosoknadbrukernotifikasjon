package no.nav.helse.flex.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.serializers.KafkaAvroSerializer
import no.nav.brukernotifikasjon.schemas.Done
import no.nav.brukernotifikasjon.schemas.Nokkel
import no.nav.brukernotifikasjon.schemas.Oppgave
import org.apache.kafka.clients.CommonClientConfigs.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("default")
class OnPremKafkaConfig(
    @Value("\${on-prem-kafka.bootstrap-servers}") private val kafkaBootstrapServers: String,
    @Value("\${on-prem-kafka.security-protocol}") private val kafkaSecurityProtocol: String,
    @Value("\${on-prem-kafka.username}") private val serviceuserUsername: String,
    @Value("\${on-prem-kafka.password}") private val serviceuserPassword: String,
    @Value("\${on-prem-kafka.schema-registry-url}") private val kafkaSchemaRegistryUrl: String,

) {

    private fun commonConfig(): Map<String, String> {
        return mapOf(
            BOOTSTRAP_SERVERS_CONFIG to kafkaBootstrapServers,
            SECURITY_PROTOCOL_CONFIG to kafkaSecurityProtocol,
            SaslConfigs.SASL_JAAS_CONFIG to "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${serviceuserUsername}\" password=\"${serviceuserPassword}\";",
            SaslConfigs.SASL_MECHANISM to "PLAIN"
        )
    }

    private fun commonProducerConfig(
        keySerializer: Class<*>,
        valueSerializer: Class<*>
    ): Map<String, Any> {
        return mapOf(
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to "true",
            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION to "1",
            ProducerConfig.MAX_BLOCK_MS_CONFIG to "15000",
            ProducerConfig.RETRIES_CONFIG to "100000",
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to valueSerializer,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to keySerializer
        ) + commonConfig()
    }

    private fun <K, V> skapBrukernotifikasjonKafkaProducer(): KafkaProducer<K, V> =
        KafkaProducer(
            commonProducerConfig(
                keySerializer = KafkaAvroSerializer::class.java,
                valueSerializer = KafkaAvroSerializer::class.java
            ) + mapOf(
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to kafkaSchemaRegistryUrl
            )
        )

    @Bean
    fun oppgaveKafkaProducer(): KafkaProducer<Nokkel, Oppgave> {
        return skapBrukernotifikasjonKafkaProducer()
    }

    @Bean
    fun doneKafkaProducer(): KafkaProducer<Nokkel, Done> {
        return skapBrukernotifikasjonKafkaProducer()
    }
}
