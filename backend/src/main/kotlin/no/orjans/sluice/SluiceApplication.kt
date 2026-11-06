package no.orjans.sluice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SluiceApplication

fun main(args: Array<String>) {
    runApplication<SluiceApplication>(*args)
}
