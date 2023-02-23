package no.nav.helse.flex.domene

import org.springframework.data.annotation.Id
import java.time.Instant

data class Brukernotifikasjon(
    @Id
    val soknadsid: String,
    val grupperingsid: String,
    val fnr: String,
    val soknadstype: Soknadstype?,
    val eksterntVarsel: Boolean,
    val oppgaveSendt: Instant?,
    val doneSendt: Instant?,
    val utsendelsestidspunkt: Instant?
)
