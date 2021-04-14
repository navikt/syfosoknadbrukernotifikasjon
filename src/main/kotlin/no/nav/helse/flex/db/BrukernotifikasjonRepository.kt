package no.nav.helse.flex.db

import no.nav.helse.flex.domene.Brukernotifikasjon
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
            INSERT INTO brukernotifikasjon(SOKNADSID, GRUPPERINGSID, FNR, OPPGAVE_SENDT)
            VALUES (:soknadsid, :grupperingsid, :fnr, :oppgaveSendt )
            """
    )
    fun insert(soknadsid: String, grupperingsid: String, fnr: String, oppgaveSendt: Instant?)
}
