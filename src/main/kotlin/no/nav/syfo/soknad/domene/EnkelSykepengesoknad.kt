package no.nav.syfo.soknad.domene

import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.syfo.objectMapper
import java.time.LocalDateTime

data class EnkelSykepengesoknad(
    val id: String,
    val status: Soknadsstatus,
    val type: Soknadstype,
    val fnr: String?,
    val sykmeldingId: String?,
    val opprettet: LocalDateTime
)

enum class Soknadsstatus {
    NY,
    SENDT,
    FREMTIDIG,
    KORRIGERT,
    AVBRUTT,
    SLETTET
}

enum class Soknadstype {
    SELVSTENDIGE_OG_FRILANSERE,
    OPPHOLD_UTLAND,
    ARBEIDSTAKERE,
    ANNET_ARBEIDSFORHOLD,
    ARBEIDSLEDIG,
    BEHANDLINGSDAGER,
    REISETILSKUDD
}

fun String.tilEnkelSykepengesoknad(): EnkelSykepengesoknad = objectMapper.readValue(this)
