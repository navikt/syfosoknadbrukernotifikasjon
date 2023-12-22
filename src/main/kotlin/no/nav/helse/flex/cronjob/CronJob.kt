package no.nav.helse.flex.cronjob

import no.nav.helse.flex.brukernotifikasjon.BrukernotifikasjonOpprettelse
import no.nav.helse.flex.logger
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class CronJob(
    val leaderElection: LeaderElection,
    val brukernotifikasjonOpprettelse: BrukernotifikasjonOpprettelse,
) {
    val log = logger()

    @Scheduled(
        initialDelay = 2,
        fixedDelay = 10,
        timeUnit = TimeUnit.MINUTES,
    )
    fun run() {
        if (leaderElection.isLeader()) {
            log.info("Kjører brukernotifikasjonjob")
            val antall = brukernotifikasjonOpprettelse.opprettBrukernotifikasjoner()
            log.info("Ferdig med brukernotifikasjonjob. $antall notifikasjoner sendt")
        } else {
            log.info("Kjører ikke brukernotifikasjonjob siden denne podden ikke er leader")
        }
    }
}
