package no.nav.helse.flex.cronjob

import no.nav.helse.flex.brukernotifikasjon.BrukernotifikasjonOpprettelse
import no.nav.helse.flex.brukernotifikasjon.BrukernotifikasjonPlanlegger
import no.nav.helse.flex.logger
import no.nav.helse.flex.util.osloZone
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

@Component
class CronJob(
    val leaderElection: LeaderElection,
    val brukernotifikasjonOpprettelse: BrukernotifikasjonOpprettelse,
    val brukernotifikasjonPlanlegger: BrukernotifikasjonPlanlegger,
) {
    val log = logger()

    //    @Scheduled(
//        initialDelay = 2,
//        fixedDelay = 10,
//        timeUnit = TimeUnit.MINUTES,
//    )
    fun run() {
        if (leaderElection.isLeader()) {
            log.info("Kjører brukernotifikasjonjob")
            val antall = brukernotifikasjonOpprettelse.opprettBrukernotifikasjoner()
            log.info("Ferdig med brukernotifikasjonjob. $antall notifikasjoner sendt")
        } else {
            log.info("Kjører ikke brukernotifikasjonjob siden denne podden ikke er leader")
        }
    }

    @Scheduled(
        initialDelay = 3,
        fixedDelay = 86400,
        timeUnit = TimeUnit.MINUTES,
    )
    fun resettSendVarsel() {
        val utsendelsestidspunkt = ZonedDateTime.of(2026, 6, 2, 9, 0, 0, 0, osloZone).toInstant()
        if (leaderElection.isLeader()) {
            listOf(
                "22c1dc3f-c925-32ae-9e47-6a1f29783e49",
                "2838acb0-ee06-3290-9acc-2e2a2302ed4c",
                "2a494c7d-dfbd-3102-aa62-f2c58b348691",
                "2b371f30-b4b2-3578-aa55-c4304187a1ac",
                "3db6db81-a11b-3617-b91b-7daa85397acc",
                "3e408ff4-9037-381b-884f-009df0a32548",
                "4756904c-3bec-35e5-82e6-bd06e59f9792",
                "47fbfa07-7287-3cee-be87-5998f4f5f62c",
                "49825a08-a1fa-3786-92c2-81eb77c3e66f",
                "521117f1-2dde-35b0-b74b-d7eee1afe986",
                "69ecf721-6bb9-3f6c-b0f5-73aba6f03031",
                "6af1e98d-5e66-3764-94e3-f7162b668047",
                "73bdccca-2f99-3075-abc5-f101830ad6f6",
                "844aeeb3-72fe-347a-9b62-b845a7bb6a38",
                "845155fb-ca32-3e66-b696-4912ec2f3264",
                "a12279ed-e56c-39f3-9918-a17dc083f23f",
                "a23214ef-d0bd-3f50-b40f-79d7332c948a",
                "b20727eb-8fd6-3190-b8f9-a8464dbc9e1c",
                "c783542d-c20a-33a2-93e8-19f1bc54b24a",
                "c81d6f3b-3ddf-3df7-a0f7-af9b8f2b0969",
                "d533e2ce-f0de-3383-ae5b-2d349e6c6cd5",
                "d84f20ca-f4d6-31ff-b733-20d7919b2527",
                "dd29b630-626c-3931-95f2-0c40a41e9085",
                "dd8b32b1-eef3-342e-95e1-b58e406b0ed9",
                "e243e6e4-3612-3677-b530-6b41c24f8656",
                "e908b32c-58d6-3972-9c93-ab6cee6a5adc",
                "eac682a3-679b-3d54-94d3-67b258f3d7e7",
                "effeb70b-f15f-3d42-8f61-5347ebcbdfa9",
                "f2fe1a35-fa13-3030-bf86-400091a8bba7",
                "f49df7a6-407d-39e1-a3a1-8e29fc0a090e",
                "f85c44b9-58e4-39aa-bf42-d221a2ee3cd0",
            ).forEach {
                brukernotifikasjonOpprettelse.resettUtsendelsestidspunkt(
                    soknadsId = it,
                    utsendelsestidspunkt = utsendelsestidspunkt,
                )
            }
        } else {
            log.info("Kjører ikke resettSendVarsel siden podden ikke er leader")
        }
    }
}
