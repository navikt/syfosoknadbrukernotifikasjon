package no.nav.helse.flex.brukernotifikasjon

import no.nav.helse.flex.domene.Brukernotifikasjon
import no.nav.helse.flex.domene.Soknadstype
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.*

class SkapOpprettVarselTest {
    @Test
    fun `Opprett varsel serialiseres som forventet`() {
        val brukernotifikasjon =
            Brukernotifikasjon(
                soknadsid = "e2ffe933-f314-4e52-800a-2c35e1e87627",
                grupperingsid = "whatever",
                doneSendt = null,
                fnr = "12345678912",
                eksterntVarsel = true,
                oppgaveSendt = null,
                soknadstype = Soknadstype.ARBEIDSTAKERE,
                utsendelsestidspunkt = OffsetDateTime.of(2020, 1, 1, 1, 1, 1, 1, ZoneOffset.UTC).toInstant(),
            )

        @Suppress("ktlint:standard:max-line-length")
        val expectedJson = """{"type":"oppgave","varselId":"e2ffe933-f314-4e52-800a-2c35e1e87627","ident":"12345678912","sensitivitet":"high","link":"https://frontende2ffe933-f314-4e52-800a-2c35e1e87627","tekster":[{"spraakkode":"nb","tekst":"Du har en søknad om sykepenger du må fylle ut","default":true}],"eksternVarsling":{"prefererteKanaler":["SMS"],"kanBatches":false},"produsent":{"cluster":"test-gcp","namespace":"flex","appnavn":"syfosoknadbrukernotifikasjon"},"metadata":{"version":"v1.1","built_at":"FIXED_TIMESTAMP","builder_lang":"kotlin"},"@event_name":"opprett"}"""

        val actualJson = brukernotifikasjon.tilOpprettVarsel("https://frontend")

        // Regex for å matche timestampet etter "built_at"
        val timestampRegex = Regex("(\"built_at\":\"[^\"]+\")")

        // Erstatt timestampet med en fast verdi i begge strengene
        val normalizedActual = timestampRegex.replace(actualJson) { """"built_at":"FIXED_TIMESTAMP"""" }

        // Sammenlign de normaliserte strengene
        normalizedActual shouldBeEqualTo expectedJson
    }
}
