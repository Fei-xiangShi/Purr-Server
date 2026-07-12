package life.fxs.purr.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.micrometer.core.instrument.MeterRegistry

fun Application.configureMonitoring(meterRegistry: MeterRegistry) {
    install(MicrometerMetrics) {
        registry = meterRegistry
    }
}
