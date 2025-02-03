package no.nav.helse.flex.domene

import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.helse.flex.objectMapper

data class EnkelSykepengesoknad(
    val id: String,
    val status: Soknadsstatus,
    val type: Soknadstype,
    val fnr: String,
    val sykmeldingId: String?,
)

enum class Soknadsstatus {
    NY,
    SENDT,
    FREMTIDIG,
    KORRIGERT,
    AVBRUTT,
    SLETTET,
    UTGAATT,
}

enum class Soknadstype {
    SELVSTENDIGE_OG_FRILANSERE,
    OPPHOLD_UTLAND,
    ARBEIDSTAKERE,
    ANNET_ARBEIDSFORHOLD,
    ARBEIDSLEDIG,
    BEHANDLINGSDAGER,
    REISETILSKUDD,
    GRADERT_REISETILSKUDD,
    FRISKMELDT_TIL_ARBEIDSFORMIDLING,
}

fun String.tilEnkelSykepengesoknad(): EnkelSykepengesoknad = objectMapper.readValue(this)
