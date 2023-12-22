package no.nav.helse.flex.brukernotifikasjon

import no.nav.helse.flex.util.osloZone
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.ZonedDateTime

class BrukernotifikasjonPlanleggerTest {
    @Test
    fun `Mandag kl 04 sendes Mandag`() {
        val mandagKl4 = ZonedDateTime.of(2022, 6, 6, 4, 12, 0, 0, osloZone)
        val utsendelsestidspunkt = finnUtsendelsestidspunkt(mandagKl4)

        utsendelsestidspunkt.year shouldBeEqualTo mandagKl4.year
        utsendelsestidspunkt.month shouldBeEqualTo mandagKl4.month
        utsendelsestidspunkt.dayOfWeek shouldBeEqualTo DayOfWeek.MONDAY
        utsendelsestidspunkt > mandagKl4
    }

    @Test
    fun `Tirsdag kl 09 sendes Onsdag`() {
        val tirsdagKl9 = ZonedDateTime.of(2022, 6, 7, 9, 12, 0, 0, osloZone)
        val utsendelsestidspunkt = finnUtsendelsestidspunkt(tirsdagKl9)

        utsendelsestidspunkt.year shouldBeEqualTo tirsdagKl9.year
        utsendelsestidspunkt.month shouldBeEqualTo tirsdagKl9.month
        utsendelsestidspunkt.dayOfWeek shouldBeEqualTo DayOfWeek.WEDNESDAY
        utsendelsestidspunkt > tirsdagKl9
    }

    @Test
    fun `Fredag kl 05 sendes Fredag`() {
        val fredagKl5 = ZonedDateTime.of(2022, 6, 10, 5, 12, 0, 0, osloZone)
        val utsendelsestidspunkt = finnUtsendelsestidspunkt(fredagKl5)

        utsendelsestidspunkt.year shouldBeEqualTo fredagKl5.year
        utsendelsestidspunkt.month shouldBeEqualTo fredagKl5.month
        utsendelsestidspunkt.dayOfWeek shouldBeEqualTo DayOfWeek.FRIDAY
        utsendelsestidspunkt > fredagKl5
    }

    @Test
    fun `Fredag kl 10 sendes neste ukes Mandag`() {
        val fredagKl10 = ZonedDateTime.of(2022, 6, 10, 10, 12, 0, 0, osloZone)
        val utsendelsestidspunkt = finnUtsendelsestidspunkt(fredagKl10)

        utsendelsestidspunkt.year shouldBeEqualTo fredagKl10.year
        utsendelsestidspunkt.month shouldBeEqualTo fredagKl10.month
        utsendelsestidspunkt.dayOfWeek shouldBeEqualTo DayOfWeek.MONDAY
        utsendelsestidspunkt > fredagKl10
    }

    @Test
    fun `Lørdag kl 11 sendes neste ukes Mandag`() {
        val lørdagKl11 = ZonedDateTime.of(2022, 6, 11, 11, 12, 0, 0, osloZone)
        val utsendelsestidspunkt = finnUtsendelsestidspunkt(lørdagKl11)

        utsendelsestidspunkt.year shouldBeEqualTo lørdagKl11.year
        utsendelsestidspunkt.month shouldBeEqualTo lørdagKl11.month
        utsendelsestidspunkt.dayOfWeek shouldBeEqualTo DayOfWeek.MONDAY
        utsendelsestidspunkt > lørdagKl11
    }

    @Test
    fun `Søndag kl 12 sendes neste ukes Mandag`() {
        val søndagKl12 = ZonedDateTime.of(2022, 6, 12, 12, 12, 0, 0, osloZone)
        val utsendelsestidspunkt = finnUtsendelsestidspunkt(søndagKl12)

        utsendelsestidspunkt.year shouldBeEqualTo søndagKl12.year
        utsendelsestidspunkt.month shouldBeEqualTo søndagKl12.month
        utsendelsestidspunkt.dayOfWeek shouldBeEqualTo DayOfWeek.MONDAY
        utsendelsestidspunkt > søndagKl12
    }
}
