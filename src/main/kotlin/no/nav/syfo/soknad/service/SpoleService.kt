package no.nav.syfo.soknad.service

import kotlinx.coroutines.delay
import no.nav.syfo.Environment
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.Topics
import no.nav.syfo.application.util.PodLeaderCoordinator
import no.nav.syfo.db.DatabaseInterface
import no.nav.syfo.kafka.envOverrides
import no.nav.syfo.kafka.loadBaseConfig
import no.nav.syfo.kafka.toConsumerConfig
import no.nav.syfo.log
import no.nav.syfo.soknad.db.finnBrukernotifikasjon
import no.nav.syfo.soknad.domene.tilEnkelSykepengesoknad
import no.nav.syfo.soknad.service.SykepengesoknadBrukernotifikasjonService.EnkelSykepengesoknadUtil.skalOppretteOppgave
import no.nav.syfo.soknad.service.SykepengesoknadBrukernotifikasjonService.EnkelSykepengesoknadUtil.skalSendeDoneMelding
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class SpoleService(
    private val podLeaderCoordinator: PodLeaderCoordinator,
    private val database: DatabaseInterface,
    private val applicationState: ApplicationState,
    private val env: Environment,
    private val delayStart: Long = 100_000L,
    private val topicName: List<String> = listOf(Topics.SYFO_SOKNAD_V2, Topics.SYFO_SOKNAD_V3)
) {
    suspend fun start() {
        try {
            // Venter til leader er overført
            log.info("SpoleService venter $delayStart ms før start")
            delay(delayStart)

            if (!podLeaderCoordinator.isLeader()) {
                log.info("SpoleService kjører bare for podLeader")
                return
            } else {
                log.info("Jeg er SpoleService leader!")
                val consumer = consumer()
                seek(consumer)
                job(consumer)
                log.info("Avslutter SpoleService")
            }
        } catch (e: Exception) {
            log.info("SpoleService exception: ${e.message}", e)
        }
    }

    private fun consumer(): KafkaConsumer<String, String> {
        val config = loadBaseConfig(env, env.hentKafkaCredentials()).envOverrides()
        config["auto.offset.reset"] = "none"
        val properties = config.toConsumerConfig("syfosoknadbrukernotifikasjon-spole-consumer", StringDeserializer::class)
        properties.let {
            it[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = "1"
        }
        return KafkaConsumer(properties)
    }

    private fun seek(consumer: KafkaConsumer<String, String>) {
        // TargetTime in ms
        val targetTime = ZonedDateTime.of(
            2020, 11, 4, 2, 0, 0, 0,
            ZoneId.of("Europe/Oslo")
        ).toInstant().toEpochMilli().also { log.info("SpoleService targetTime = $it") }
        // Get the list of partitions
        val partitionInfos = topicName
            .flatMap { topic -> consumer.partitionsFor(topic) }
        // Transform PartitionInfo into TopicPartition
        val topicPartitionList: List<TopicPartition> = partitionInfos
            .map { info -> TopicPartition(info.topic(), info.partition()) }
        // Assign the consumer to these partitions
        consumer.assign(topicPartitionList)
        // Look for offsets based on timestamp
        val partitionTimestampMap: Map<TopicPartition, Long> = topicPartitionList
            .map { tp -> tp to targetTime }
            .toMap()
        // Find earliest offset whose timestamp is greater than or equal to the given timestamp
        val partitionOffsetMap = consumer
            .offsetsForTimes(partitionTimestampMap)
            .also { log.info("SpoleService partitionOffsetMap $it") }
        // Force the consumer to seek for those offsets
        partitionOffsetMap.forEach { (tp, offsetAndTimestamp) ->
            consumer.seek(
                tp,
                offsetAndTimestamp?.offset()
                    ?: consumer.endOffsets(listOf(tp)).getOrDefault(tp, 0)
            )
        }
    }

    private fun job(consumer: KafkaConsumer<String, String>) {
        var loop = true
        var poll = consumer.poll(Duration.ofMillis(1000))
        log.info("SpoleService starter fra timestamp: ${poll.first().timestamp()}, topic: ${poll.first().topic()}, partition: ${poll.first().partition()}")

        while (applicationState.ready && loop) {
            poll.forEach { cr ->
                cr.value().runCatching {
                    tilEnkelSykepengesoknad()
                }.onFailure { e ->
                    log.info("SpoleService kunne ikke deserialisere søknad, fortsetter", e)
                }.onSuccess { sykepengesoknad ->
                    if (sykepengesoknad.skalOppretteOppgave()) {
                        val brukernotfikasjon = database.finnBrukernotifikasjon(sykepengesoknad.id)
                        if (brukernotfikasjon == null) {
                            log.info("SpoleService finner ikke: dittnav oppgave for søknad ${sykepengesoknad.id}")
                        }
                    } else if (sykepengesoknad.skalSendeDoneMelding()) {
                        val brukernotfikasjon = database.finnBrukernotifikasjon(sykepengesoknad.id)
                        if (brukernotfikasjon != null) {
                            if (brukernotfikasjon.doneSendt == null) {
                                log.info("SpoleService finner ikke: done melding for søknad ${sykepengesoknad.id}")
                            }
                        }
                    }
                }
                consumer.commitSync()
            }

            poll = consumer.poll(Duration.ofMillis(1000))
            if (poll.isEmpty) {
                loop = false
                consumer.commitSync()
            }
        }
    }
}
