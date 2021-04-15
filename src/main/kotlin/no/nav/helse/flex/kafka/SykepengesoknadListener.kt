package no.nav.helse.flex.kafka

import no.nav.helse.flex.service.SykepengesoknadBrukernotifikasjonService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

const val SYFO_SOKNAD_V2 = "syfo-soknad-v2"
const val SYFO_SOKNAD_V3 = "syfo-soknad-v3"

@Component
class SykepengesoknadListener(
    private val sykepengesoknadBrukernotifikasjonService: SykepengesoknadBrukernotifikasjonService,
) {

    @KafkaListener(
        topics = [SYFO_SOKNAD_V2, SYFO_SOKNAD_V3],
        containerFactory = "onpremKafkaListenerContainerFactory"
    )
    fun listen(cr: ConsumerRecord<String, String>, acknowledgment: Acknowledgment) {
        sykepengesoknadBrukernotifikasjonService.handterSykepengesoknad(cr.value())
        acknowledgment.acknowledge()
    }
}
