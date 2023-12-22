package no.nav.helse.flex.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.serializers.KafkaAvroSerializer
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig
import no.nav.brukernotifikasjon.schemas.input.DoneInput
import no.nav.brukernotifikasjon.schemas.input.NokkelInput
import no.nav.brukernotifikasjon.schemas.input.OppgaveInput
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("default")
class ProducerKafkaConfig(
    private val aivenKafkaConfig: AivenKafkaConfig,
    @Value("\${KAFKA_SCHEMA_REGISTRY}") private val kafkaSchemaRegistryUrl: String,
    @Value("\${KAFKA_SCHEMA_REGISTRY_USER}") private val kafkaSchemaUsername: String,
    @Value("\${KAFKA_SCHEMA_REGISTRY_PASSWORD}") private val kafkaSchemaPassword: String,
) {
    private fun producerConfig(): Map<String, Any> {
        return mapOf(
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to "true",
            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION to "1",
            ProducerConfig.MAX_BLOCK_MS_CONFIG to "15000",
            ProducerConfig.RETRIES_CONFIG to "100000",
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to KafkaAvroSerializer::class.java,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to KafkaAvroSerializer::class.java,
            AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to kafkaSchemaRegistryUrl,
            KafkaAvroSerializerConfig.USER_INFO_CONFIG to "$kafkaSchemaUsername:$kafkaSchemaPassword",
            KafkaAvroSerializerConfig.BASIC_AUTH_CREDENTIALS_SOURCE to "USER_INFO",
            SaslConfigs.SASL_MECHANISM to "PLAIN",
        ) + aivenKafkaConfig.commonConfig()
    }

    @Bean
    fun oppgaveKafkaProducer() = KafkaProducer<NokkelInput, OppgaveInput>(producerConfig())

    @Bean
    fun doneKafkaProducer() = KafkaProducer<NokkelInput, DoneInput>(producerConfig())
}
