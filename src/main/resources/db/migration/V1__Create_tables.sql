CREATE TABLE brukernotifikasjon
(
    soknadsid VARCHAR(36) PRIMARY KEY,
    grupperingsid VARCHAR(36),
    fnr VARCHAR(11) NOT NULL,
    oppgave_sendt TIMESTAMP WITH TIME ZONE NOT NULL,
    done_sendt TIMESTAMP WITH TIME ZONE
);

