package no.nav.syfo

data class Environment(
    val applicationPort: Int = getEnvVar("APPLICATION_PORT", "8080").toInt(),
    val applicationName: String = getEnvVar("NAIS_APP_NAME"),
    val cluster: String = getEnvVar("NAIS_CLUSTER_NAME"),
    val dbHost: String = getEnvVar("NAIS_DATABASE_SYFOSOKNADBRUKERNOTIFIKASJON_SYFOSOKNADBRUKERNOTIFIKASJON_DB_HOST"),
    val dbPort: String = getEnvVar("NAIS_DATABASE_SYFOSOKNADBRUKERNOTIFIKASJON_SYFOSOKNADBRUKERNOTIFIKASJON_DB_PORT"),
    val dbName: String = getEnvVar("NAIS_DATABASE_SYFOSOKNADBRUKERNOTIFIKASJON_SYFOSOKNADBRUKERNOTIFIKASJON_DB_DATABASE"),
    val dbUsername: String = getEnvVar("NAIS_DATABASE_SYFOSOKNADBRUKERNOTIFIKASJON_SYFOSOKNADBRUKERNOTIFIKASJON_DB_USERNAME"),
    val dbPassword: String = getEnvVar("NAIS_DATABASE_SYFOSOKNADBRUKERNOTIFIKASJON_SYFOSOKNADBRUKERNOTIFIKASJON_DB_PASSWORD"),
    val serviceuserUsername: String = getEnvVar("SERVICEUSER_USERNAME"),
    val sykepengesoknadFrontend: String = getEnvVar("SYKEPENGESOKNAD_FRONTEND_URL"),
    val serviceuserPassword: String = getEnvVar("SERVICEUSER_PASSWORD"),
    val sidecarInitialDelay: Long = getEnvVar("SIDECAR_INITIAL_DELAY", "15000").toLong(),
    val kafkaBootstrapServers: String = getEnvVar("KAFKA_BOOTSTRAP_SERVERS_URL"),
    val kafkaAutoOffsetReset: String = getEnvVar("KAFKA_AUTO_OFFSET_RESET", "none"),
    val kafkaSecurityProtocol: String = getEnvVar("KAFKA_SECURITY_PROTOCOL", "SASL_SSL"),
    val kafkaSchemaRegistryUrl: String = getEnvVar("KAFKA_SCHEMA_REGISTRY_URL")
) {

    fun isProd(): Boolean {
        return cluster == "prod-gcp"
    }

    fun jdbcUrl(): String {
        return "jdbc:postgresql://$dbHost:$dbPort/$dbName"
    }
}

fun getEnvVar(varName: String, defaultValue: String? = null) =
    System.getenv(varName) ?: defaultValue ?: throw RuntimeException("Missing required variable \"$varName\"")
