package no.nav.syfo.soknad.db

import no.nav.syfo.db.DatabaseInterface
import no.nav.syfo.db.toList
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime

fun DatabaseInterface.finnBrukernotifikasjon(soknadsid: String): Brukernotifikasjon? =
    connection.use {
        return it.finnBrukernotifikasjon(soknadsid)
    }

fun DatabaseInterface.opprettBrukernotifikasjon(
    soknadsid: String,
    grupperingsid: String,
    fnr: String,
    oppgaveSendt: Instant
) =
    connection.use {
        it.opprettBrukernotifikasjon(
            soknadsid = soknadsid,
            grupperingsid = grupperingsid,
            fnr = fnr,
            oppgaveSendt = oppgaveSendt
        )
    }

fun DatabaseInterface.settDoneSendt(soknadsid: String) {
    connection.use {
        return it.settDoneSendt(soknadsid)
    }
}

private fun Connection.opprettBrukernotifikasjon(
    soknadsid: String,
    grupperingsid: String,
    fnr: String,
    oppgaveSendt: Instant
) {

    this.prepareStatement(
        """
            INSERT INTO BRUKERNOTIFIKASJON(soknadsid, grupperingsid, fnr, oppgave_sendt) VALUES (?, ?, ?, ?) 
        """
    ).use {
        it.setString(1, soknadsid)
        it.setString(2, grupperingsid)
        it.setString(3, fnr)
        it.setTimestamp(4, Timestamp.from(oppgaveSendt))

        it.executeUpdate()
    }
    this.commit()
}

private fun Connection.finnBrukernotifikasjon(soknadsid: String): Brukernotifikasjon? {
    return this.prepareStatement(
        """
            SELECT soknadsid, grupperingsid, fnr, oppgave_sendt, done_sendt
            FROM brukernotifikasjon
            WHERE soknadsid = ?;
            """
    ).use {
        it.setString(1, soknadsid)
        it.executeQuery()
            .toList { toBrukernotifikasjon() }
            .firstOrNull()
    }
}

private fun Connection.settDoneSendt(soknadsid: String) {
    this.prepareStatement(
        """
        UPDATE brukernotifikasjon
        SET done_sendt = ?
        WHERE soknadsid = ?
        """
    ).use {
        it.setTimestamp(1, Timestamp.from(Instant.now()))
        it.setString(2, soknadsid)
        it.executeUpdate()
    }
    this.commit()
}

private fun ResultSet.toBrukernotifikasjon(): Brukernotifikasjon =
    Brukernotifikasjon(
        soknadsid = getString("soknadsid"),
        grupperingsid = getString("grupperingsid"),
        fnr = getString("fnr"),
        oppgaveSendt = getObject("oppgave_sendt", OffsetDateTime::class.java).toInstant(),
        doneSendt = getObject("done_sendt", OffsetDateTime::class.java)?.toInstant()
    )

data class Brukernotifikasjon(
    val soknadsid: String,
    val grupperingsid: String,
    val fnr: String,
    val oppgaveSendt: Instant,
    val doneSendt: Instant?
)
