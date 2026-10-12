package no.nav.helse.flex

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.scheduling.annotation.EnableScheduling
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

@SpringBootApplication
@EnableKafka
@EnableScheduling
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

val objectMapper: ObjectMapper =
    JsonMapper
        .builder()
        .addModule(kotlinModule())
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .build()
