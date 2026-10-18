package no.nav.helse.flex.kafka

import no.nav.helse.flex.domene.EnkelSykepengesoknad
import no.nav.helse.flex.domene.Soknadsstatus
import no.nav.helse.flex.domene.Soknadstype
import no.nav.helse.flex.sykepengesoknad.kafka.SoknadsstatusDTO
import no.nav.helse.flex.sykepengesoknad.kafka.SoknadstypeDTO
import no.nav.helse.flex.sykepengesoknad.kafka.SykepengesoknadDTO

fun SykepengesoknadDTO.tilEnkelSykepengesoknad(): EnkelSykepengesoknad =
    EnkelSykepengesoknad(
        id = id,
        status = status.tilSoknadsstatus(),
        type = type.tilSoknadstype(),
        fnr = fnr,
        sykmeldingId = sykmeldingId,
    )

internal fun SoknadsstatusDTO.tilSoknadsstatus(): Soknadsstatus =
    when (this) {
        SoknadsstatusDTO.NY -> Soknadsstatus.NY
        SoknadsstatusDTO.SENDT -> Soknadsstatus.SENDT
        SoknadsstatusDTO.FREMTIDIG -> Soknadsstatus.FREMTIDIG
        SoknadsstatusDTO.KORRIGERT -> Soknadsstatus.KORRIGERT
        SoknadsstatusDTO.AVBRUTT -> Soknadsstatus.AVBRUTT
        SoknadsstatusDTO.SLETTET -> Soknadsstatus.SLETTET
        SoknadsstatusDTO.UTGAATT -> Soknadsstatus.UTGAATT
    }

internal fun SoknadstypeDTO.tilSoknadstype(): Soknadstype =
    when (this) {
        SoknadstypeDTO.SELVSTENDIGE_OG_FRILANSERE -> Soknadstype.SELVSTENDIGE_OG_FRILANSERE
        SoknadstypeDTO.OPPHOLD_UTLAND -> Soknadstype.OPPHOLD_UTLAND
        SoknadstypeDTO.ARBEIDSTAKERE -> Soknadstype.ARBEIDSTAKERE
        SoknadstypeDTO.ANNET_ARBEIDSFORHOLD -> Soknadstype.ANNET_ARBEIDSFORHOLD
        SoknadstypeDTO.ARBEIDSLEDIG -> Soknadstype.ARBEIDSLEDIG
        SoknadstypeDTO.BEHANDLINGSDAGER -> Soknadstype.BEHANDLINGSDAGER
        SoknadstypeDTO.REISETILSKUDD -> Soknadstype.REISETILSKUDD
        SoknadstypeDTO.GRADERT_REISETILSKUDD -> Soknadstype.GRADERT_REISETILSKUDD
        SoknadstypeDTO.FRISKMELDT_TIL_ARBEIDSFORMIDLING -> Soknadstype.FRISKMELDT_TIL_ARBEIDSFORMIDLING
        SoknadstypeDTO.FISKERE -> Soknadstype.FISKERE
    }
