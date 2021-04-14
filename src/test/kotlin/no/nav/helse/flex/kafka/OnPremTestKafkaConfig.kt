package no.nav.helse.flex.kafka

import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig
import io.confluent.kafka.serializers.KafkaAvroSerializer
import no.nav.brukernotifikasjon.schemas.Done
import no.nav.brukernotifikasjon.schemas.Nokkel
import no.nav.brukernotifikasjon.schemas.Oppgave
import no.nav.helse.flex.brukernotifkasjon.DONE_TOPIC
import no.nav.helse.flex.brukernotifkasjon.OPPGAVE_TOPIC
import org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG
import org.apache.kafka.clients.CommonClientConfigs.SECURITY_PROTOCOL_CONFIG
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.listener.ContainerProperties
import java.io.Serializable
import java.util.HashMap

@Configuration
@Profile("test")
class OnPremTestKafkaConfig(
    @Value("\${on-prem-kafka.bootstrap-servers}") private val kafkaBootstrapServers: String

) {

    private fun commonConfig(): Map<String, String> {
        return mapOf(
            BOOTSTRAP_SERVERS_CONFIG to kafkaBootstrapServers,
            SECURITY_PROTOCOL_CONFIG to "PLAINTEXT"
        )
    }

    @Bean
    fun consumerFactory(): ConsumerFactory<String, String> {
        val config = mapOf(
            ConsumerConfig.GROUP_ID_CONFIG to "syfosoknadbrukernotifikasjon-consumer",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "latest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to "1"
        ) + commonConfig()
        return DefaultKafkaConsumerFactory(config)
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        kafkaErrorHandler: KafkaErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.consumerFactory = consumerFactory
        factory.setErrorHandler(kafkaErrorHandler)
        factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
        return factory
    }

    private fun producerConfig(): Map<String, Serializable> {
        return mapOf(
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to "true",
            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION to "1",
            ProducerConfig.MAX_BLOCK_MS_CONFIG to "15000",
            ProducerConfig.RETRIES_CONFIG to "100000",
        )
    }

    private fun avroProducerConfig(): Map<String, Serializable> {
        return mapOf(
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to KafkaAvroSerializer::class.java,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to KafkaAvroSerializer::class.java,
            AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to "http://whatever.nav",
            SaslConfigs.SASL_MECHANISM to "PLAIN"
        ) + producerConfig() + commonConfig()
    }

    @Bean
    fun kafkaProducer(): KafkaProducer<String, String> {
        val conf = mapOf(
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ) + producerConfig() + commonConfig()
        return KafkaProducer(conf)
    }

    @Bean
    fun mockSchemaRegistryClient(): MockSchemaRegistryClient {
        val mockSchemaRegistryClient = MockSchemaRegistryClient()

        mockSchemaRegistryClient.register("$OPPGAVE_TOPIC-value", AvroSchema(Oppgave.`SCHEMA$`))
        mockSchemaRegistryClient.register("$OPPGAVE_TOPIC-value", AvroSchema(Nokkel.`SCHEMA$`))
        mockSchemaRegistryClient.register("$DONE_TOPIC-value", AvroSchema(Done.`SCHEMA$`))
        mockSchemaRegistryClient.register("$DONE_TOPIC-value", AvroSchema(Nokkel.`SCHEMA$`))
        return mockSchemaRegistryClient
    }

    fun kafkaAvroDeserializer(): KafkaAvroDeserializer {
        val config = HashMap<String, Any>()
        config[AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS] = false
        config[KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG] = true
        config[KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG] = "http://ikke.i.bruk.nav"
        return KafkaAvroDeserializer(mockSchemaRegistryClient(), config)
    }

    fun testConsumerProps(groupId: String) = mapOf(
        ConsumerConfig.GROUP_ID_CONFIG to groupId,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ConsumerConfig.MAX_POLL_RECORDS_CONFIG to "1"
    ) + commonConfig()

    @Bean
    fun oppgaveKafkaConsumer(): Consumer<Nokkel, Oppgave> {
        @Suppress("UNCHECKED_CAST")
        return DefaultKafkaConsumerFactory(
            testConsumerProps("oppgave-consumer"),
            kafkaAvroDeserializer() as Deserializer<Nokkel>,
            kafkaAvroDeserializer() as Deserializer<Oppgave>
        ).createConsumer()
    }

    @Bean
    fun doneoppgaveKafkaConsumer(): Consumer<Nokkel, Done> {
        @Suppress("UNCHECKED_CAST")
        return DefaultKafkaConsumerFactory(
            testConsumerProps("done-konsumer"),
            kafkaAvroDeserializer() as Deserializer<Nokkel>,
            kafkaAvroDeserializer() as Deserializer<Done>
        ).createConsumer()
    }

    @Bean
    fun oppgaveKafkaProducer(mockSchemaRegistryClient: MockSchemaRegistryClient): Producer<Nokkel, Oppgave> {
        val kafkaAvroSerializer = KafkaAvroSerializer(mockSchemaRegistryClient)
        @Suppress("UNCHECKED_CAST")
        return DefaultKafkaProducerFactory(
            avroProducerConfig(),
            kafkaAvroSerializer as Serializer<Nokkel>,
            kafkaAvroSerializer as Serializer<Oppgave>
        ).createProducer()
    }

    @Bean
    fun doneKafkaProducer(mockSchemaRegistryClient: MockSchemaRegistryClient): Producer<Nokkel, Done> {
        val kafkaAvroSerializer = KafkaAvroSerializer(mockSchemaRegistryClient)
        @Suppress("UNCHECKED_CAST")
        return DefaultKafkaProducerFactory(
            avroProducerConfig(),
            kafkaAvroSerializer as Serializer<Nokkel>,
            kafkaAvroSerializer as Serializer<Done>
        ).createProducer()
    }
}
