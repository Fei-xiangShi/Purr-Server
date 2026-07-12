package life.fxs.purr.server.monitoring

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.util.concurrent.atomic.AtomicBoolean

class ServerMetrics : AutoCloseable {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val gcMetrics = JvmGcMetrics()
    private val closed = AtomicBoolean(false)

    init {
        ClassLoaderMetrics().bindTo(registry)
        gcMetrics.bindTo(registry)
        JvmMemoryMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
        FileDescriptorMetrics().bindTo(registry)
        UptimeMetrics().bindTo(registry)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        gcMetrics.close()
        registry.close()
    }
}
