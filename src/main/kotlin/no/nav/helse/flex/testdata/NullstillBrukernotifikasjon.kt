package no.nav.helse.flex.testdata

import no.nav.helse.flex.brukernotifikasjon.BrukernotifikasjonRepository
import no.nav.helse.flex.kafka.nyttVarselTopic
import no.nav.helse.flex.logger
import no.nav.tms.varsel.builder.VarselActionBuilder
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("testdatareset")
class NullstillBrukernotifikasjon(
    private val brukernotifikasjonRepository: BrukernotifikasjonRepository,
    private val kafkaProducer: KafkaProducer<String, String>,
) {
    private val log = logger()

    fun nullstill(fnr: String): Int {
        var antall = 0

        brukernotifikasjonRepository
            .findByFnr(fnr)
            .forEach {
                if (it.oppgaveSendt != null && it.doneSendt == null) {
                    log.info("Sender done melding med id ${it.soknadsid} og grupperingsid ${it.grupperingsid}")

                    val inaktiverVarsel =
                        VarselActionBuilder.inaktiver {
                            varselId = it.soknadsid
                        }

                    kafkaProducer.send(ProducerRecord(nyttVarselTopic, it.soknadsid, inaktiverVarsel)).get()
                }

                brukernotifikasjonRepository.deleteById(it.soknadsid)
                antall++
            }

        return antall
    }
}
