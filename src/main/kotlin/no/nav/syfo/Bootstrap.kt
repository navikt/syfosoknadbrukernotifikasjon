package no.nav.syfo

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.client.hotspot.DefaultExports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import no.nav.syfo.application.ApplicationServer
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.createApplicationEngine
import no.nav.syfo.application.util.KafkaClients
import no.nav.syfo.application.util.PodLeaderCoordinator
import no.nav.syfo.brukernotifkasjon.skapBrukernotifikasjonKafkaProducer
import no.nav.syfo.db.Database
import no.nav.syfo.kafka.envOverrides
import no.nav.syfo.kafka.loadBaseConfig
import no.nav.syfo.soknad.kafka.SyfosoknadKafkaPoller
import no.nav.syfo.soknad.service.SpoleService
import no.nav.syfo.soknad.service.SykepengesoknadBrukernotifikasjonService
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val log: Logger = LoggerFactory.getLogger("no.nav.syfo.syfosoknadbrukernotifikasjon")

val objectMapper: ObjectMapper = ObjectMapper().apply {
    registerKotlinModule()
    registerModule(JavaTimeModule())
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
}

@KtorExperimentalAPI
fun main() {
    val env = Environment()

    val kafkaClients = KafkaClients(env)
    val applicationState = ApplicationState()

    DefaultExports.initialize()

    // Sov litt slik at sidecars er klare
    Thread.sleep(env.sidecarInitialDelay)
    log.info("Sov i ${env.sidecarInitialDelay} ms i håp om at sidecars er klare")

    val database = Database(env)

    val kafkaBaseConfig = loadBaseConfig(env, env.hentKafkaCredentials()).envOverrides()

    val brukernotifikasjonKafkaProducer = skapBrukernotifikasjonKafkaProducer(kafkaBaseConfig)
    val syfosoknadKafkaPoller = SyfosoknadKafkaPoller(kafkaClients.syfoSoknadConsumer)
    val sykepengesoknadBrukernotifikasjonService = SykepengesoknadBrukernotifikasjonService(
        database = database,
        applicationState = applicationState,
        syfosoknadKafkaPoller = syfosoknadKafkaPoller,
        brukernotifikasjonKafkaProducer = brukernotifikasjonKafkaProducer,
        servicebruker = "srvsyfosokbrukerntf",
        environment = env
    )

    applicationState.ready = true

    val applicationEngine = createApplicationEngine(env, applicationState)

    val applicationServer = ApplicationServer(applicationEngine, applicationState)
    applicationServer.start()

    createListener(applicationState) {
        sykepengesoknadBrukernotifikasjonService.start()
    }

    val spoleService = SpoleService(
        podLeaderCoordinator = PodLeaderCoordinator(env = env),
        database = database,
        applicationState = applicationState,
        env = env,
        sykepengesoknadBrukernotifikasjonService = sykepengesoknadBrukernotifikasjonService
    )
    GlobalScope.launch {
        spoleService.start()
    }
}

fun createListener(applicationState: ApplicationState, action: suspend CoroutineScope.() -> Unit): Job =
    GlobalScope.launch {
        try {
            action()
        } catch (ex: Exception) {
            log.error("Noe gikk galt: {}", ex.message)
        } finally {
            applicationState.alive = false
            applicationState.ready = false
        }
    }
