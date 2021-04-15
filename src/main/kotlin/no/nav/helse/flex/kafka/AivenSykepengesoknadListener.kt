package no.nav.helse.flex.kafka

import no.nav.helse.flex.logger
import no.nav.helse.flex.service.SykepengesoknadBrukernotifikasjonService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.event.EventListener
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.event.ConsumerStoppedEvent
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

const val SYKEPENGESOKNAD_TOPIC = "flex." + "sykepengesoknad"

@Component
class AivenSykepengesoknadListener(
    private val sykepengesoknadBrukernotifikasjonService: SykepengesoknadBrukernotifikasjonService,
) {

    private val log = logger()

    @KafkaListener(
        topics = [SYKEPENGESOKNAD_TOPIC],
        containerFactory = "aivenKafkaListenerContainerFactory"
    )
    fun listen(cr: ConsumerRecord<String, String>, acknowledgment: Acknowledgment) {

        sykepengesoknadBrukernotifikasjonService.handterSykepengesoknad(cr.value())
        acknowledgment.acknowledge()
    }

    @EventListener
    fun eventHandler(event: ConsumerStoppedEvent) {
        if (event.reason == ConsumerStoppedEvent.Reason.NORMAL) {
            return
        }
        log.error("Consumer stoppet grunnet ${event.reason}")
        if (event.source is KafkaMessageListenerContainer<*, *> &&
            event.reason == ConsumerStoppedEvent.Reason.AUTH
        ) {
            val container = event.source as KafkaMessageListenerContainer<*, *>
            log.info("Trying to restart consumer, creds may be rotated")
            container.start()
        }
    }
}
