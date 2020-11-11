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
import no.nav.syfo.soknad.domene.EnkelSykepengesoknad
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
    private val sykepengesoknadBrukernotifikasjonService: SykepengesoknadBrukernotifikasjonService,
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
                    val kandidat = rebehandlinger.find { it.soknadId == sykepengesoknad.id }
                    if (kandidat != null) {
                        kandidat.sisteSoknad = sykepengesoknad
                        consumer.commitSync()
                    }
                }
            }

            poll = consumer.poll(Duration.ofMillis(1000))
            if (poll.isEmpty) {
                loop = false
                consumer.commitSync()
            }
        }

        rebehandlinger.forEach {
            if (it.sisteSoknad == null) {
                log.info("SpoleService finner ikke søknad ${it.soknadId}, skal ikke skje!")
            } else if (it.sisteSoknad!!.skalOppretteOppgave() && database.finnBrukernotifikasjon(it.soknadId) == null) {
                log.info("SpoleService oppretter oppgave for ${it.soknadId}")
                sykepengesoknadBrukernotifikasjonService.handterSykepengesoknad(it.sisteSoknad!!)
            } else if (it.sisteSoknad!!.skalSendeDoneMelding() && database.finnBrukernotifikasjon(it.soknadId) != null && database.finnBrukernotifikasjon(it.soknadId)?.doneSendt == null) {
                log.info("SpoleService sender done melding for ${it.soknadId}")
                sykepengesoknadBrukernotifikasjonService.handterSykepengesoknad(it.sisteSoknad!!)
            } else {
                log.info("SpoleService gikk til else for ${it.soknadId}, skal ikke skje!")
            }
        }
    }

    companion object Rebehandlinger {
        val rebehandlinger = listOf(
            Rebehandling(soknadId = "e8dea914-473a-4b09-9c18-a115e61ea80d"),
            Rebehandling(soknadId = "118bc72c-11af-4a82-acaa-8e7ac0e21b58"),
            Rebehandling(soknadId = "0839a1b3-9385-4346-a3cb-8327cbd16490"),
            Rebehandling(soknadId = "63c03823-1025-4065-a6bc-664e294eaf41"),
            Rebehandling(soknadId = "c5e825b7-aee3-448c-8e63-ee2826fecffd"),
            Rebehandling(soknadId = "9e33fe59-1904-4298-86cb-7bc7e59daf4c"),
            Rebehandling(soknadId = "63abb776-60d4-454e-add1-f95cf12c6264"),
            Rebehandling(soknadId = "525f6ed9-409f-4914-857f-7052598ceb76"),
            Rebehandling(soknadId = "94c79950-46a7-4958-a925-53e2b037b021"),
            Rebehandling(soknadId = "ba2b1120-a76c-441d-937d-bc68a7ef9f19"),
            Rebehandling(soknadId = "20466234-8c4a-4ca9-9a1e-6aae7f7ab74f"),
            Rebehandling(soknadId = "59c64c09-c077-4104-8aa4-8219cebeef01"),
            Rebehandling(soknadId = "76a2cab5-678f-40f1-9d86-7e188d130b63"),
            Rebehandling(soknadId = "384d5931-8b35-431f-a1cc-01f94011443c"),
            Rebehandling(soknadId = "75ce402e-cab3-4f69-b30b-9c192fdb88b9"),
            Rebehandling(soknadId = "e3699477-f4e3-4626-8948-f9082efd0fb1"),
            Rebehandling(soknadId = "7318dfd4-6394-4cd3-8271-06fa307cfdd3"),
            Rebehandling(soknadId = "89f07c44-bc7b-4440-b550-5f8b73c38dbd"),
            Rebehandling(soknadId = "4bab0103-57f1-4dcf-b751-11374ee49afd"),
            Rebehandling(soknadId = "fa109adc-2e9d-4258-a292-6c1c2524e814"),
            Rebehandling(soknadId = "3041ecb4-4da6-4bb8-94ad-f0be4214d360"),
            Rebehandling(soknadId = "2a9c50ce-db3d-40b6-841d-25cb1ad31d68"),
            Rebehandling(soknadId = "2a664894-bb53-42c1-865a-61cbe71329df")
        )
    }

    data class Rebehandling(
        val soknadId: String,
        var sisteSoknad: EnkelSykepengesoknad? = null
    )
}
