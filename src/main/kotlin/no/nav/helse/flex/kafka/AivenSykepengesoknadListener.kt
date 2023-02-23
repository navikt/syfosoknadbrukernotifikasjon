package no.nav.helse.flex.kafka

import no.nav.helse.flex.brukernotifikasjon.BrukernotifikasjonPlanlegger
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

const val SYKEPENGESOKNAD_TOPIC = "flex." + "sykepengesoknad"

@Component
class AivenSykepengesoknadListener(
    private val brukernotifikasjonPlanlegger: BrukernotifikasjonPlanlegger
) {

    @KafkaListener(
        topics = [SYKEPENGESOKNAD_TOPIC],
        containerFactory = "aivenKafkaListenerContainerFactory"
    )
    fun listen(cr: ConsumerRecord<String, String>, acknowledgment: Acknowledgment) {
        brukernotifikasjonPlanlegger.planleggBrukernotfikasjon(cr.value())
        acknowledgment.acknowledge()
    }
}
