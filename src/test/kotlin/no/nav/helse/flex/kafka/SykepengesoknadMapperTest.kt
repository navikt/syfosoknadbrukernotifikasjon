package no.nav.helse.flex.kafka

import no.nav.helse.flex.domene.EnkelSykepengesoknad
import no.nav.helse.flex.domene.Soknadsstatus
import no.nav.helse.flex.domene.Soknadstype
import no.nav.helse.flex.sykepengesoknad.kafka.SoknadsstatusDTO
import no.nav.helse.flex.sykepengesoknad.kafka.SoknadstypeDTO
import no.nav.helse.flex.sykepengesoknad.kafka.SykepengesoknadDTO
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class SykepengesoknadMapperTest {
    @Test
    fun `Mapper SykepengesoknadDTO til EnkelSykepengesoknad`() {
        val soknad =
            SykepengesoknadDTO(
                id = "soknadsid",
                status = SoknadsstatusDTO.NY,
                type = SoknadstypeDTO.ARBEIDSTAKERE,
                fnr = "fnr",
                sykmeldingId = "sykmeldingsid",
            )

        soknad.tilEnkelSykepengesoknad() shouldBeEqualTo
            EnkelSykepengesoknad(
                id = "soknadsid",
                status = Soknadsstatus.NY,
                type = Soknadstype.ARBEIDSTAKERE,
                fnr = "fnr",
                sykmeldingId = "sykmeldingsid",
            )
    }
}
