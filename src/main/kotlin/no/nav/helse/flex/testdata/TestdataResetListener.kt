package no.nav.helse.flex.testdata

import no.nav.helse.flex.logger
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
@Profile("testdatareset")
class TestdataResetListener(
    val nullstillBrukernotifikasjon: NullstillBrukernotifikasjon
) {

    val log = logger()

    @KafkaListener(
        topics = [TESTDATA_RESET_TOPIC],
        containerFactory = "aivenKafkaListenerContainerFactory",
        properties = ["auto.offset.reset = latest"]
    )
    fun listen(cr: ConsumerRecord<String, String>, acknowledgment: Acknowledgment) {
        val fnr = cr.value()
        val antall = nullstillBrukernotifikasjon.nullstill(fnr)
        log.info("Donet og slettet $antall brukernotifikasjoner tilhørende fnr: $fnr - Key ${cr.key()}.")
        acknowledgment.acknowledge()
    }
}

const val TESTDATA_RESET_TOPIC = "flex.testdata-reset"
