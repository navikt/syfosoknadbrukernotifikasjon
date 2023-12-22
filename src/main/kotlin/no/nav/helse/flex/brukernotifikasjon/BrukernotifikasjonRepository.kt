package no.nav.helse.flex.brukernotifikasjon

import no.nav.helse.flex.domene.Brukernotifikasjon
import no.nav.helse.flex.domene.Soknadstype
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface BrukernotifikasjonRepository : CrudRepository<Brukernotifikasjon, String> {
    @Modifying
    @Query(
        """
            INSERT INTO brukernotifikasjon(SOKNADSID, GRUPPERINGSID, FNR, UTSENDELSESTIDSPUNKT, SOKNADSTYPE, EKSTERNT_VARSEL)
            VALUES (:soknadsid, :grupperingsid, :fnr, :utsendelsestidspunkt, :soknadstype, :eksterntVarsel )
            """,
    )
    fun insert(
        soknadsid: String,
        grupperingsid: String,
        fnr: String,
        utsendelsestidspunkt: Instant?,
        soknadstype: Soknadstype,
        eksterntVarsel: Boolean,
    )

    fun findByUtsendelsestidspunktIsNotNullAndUtsendelsestidspunktIsBefore(now: Instant): List<Brukernotifikasjon>

    fun findByFnr(fnr: String): List<Brukernotifikasjon>
}
