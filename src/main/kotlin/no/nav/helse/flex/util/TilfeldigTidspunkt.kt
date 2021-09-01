package no.nav.helse.flex.util

import java.time.*
import java.util.concurrent.ThreadLocalRandom

fun timeMellom9og15(): Int {
    return ThreadLocalRandom.current().nextInt(9, 14)
}

fun minuttMellom0og59(): Int {
    return ThreadLocalRandom.current().nextInt(0, 59)
}

val osloZone = ZoneId.of("Europe/Oslo")
