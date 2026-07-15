package life.fxs.purr.server.monitoring

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import life.fxs.purr.server.application.port.AvatarTelemetry
import life.fxs.purr.server.application.port.AvatarUploadOutcome

class ServerMetrics : AutoCloseable, AvatarTelemetry {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val gcMetrics = JvmGcMetrics()
    private val closed = AtomicBoolean(false)
    private val avatarUploadCounters = AvatarUploadOutcome.entries.associateWith { outcome ->
        registry.counter("purr.avatar.uploads", "outcome", outcome.name.lowercase())
    }
    private val avatarInputBytes = registry.summary("purr.avatar.input.bytes")
    private val avatarOutputBytes = registry.summary("purr.avatar.output.bytes")
    private val avatarUploadDuration = registry.timer("purr.avatar.upload.duration")
    private val avatarCleanupSucceeded = registry.counter("purr.avatar.cleanup", "outcome", "succeeded")
    private val avatarCleanupFailed = registry.counter("purr.avatar.cleanup", "outcome", "failed")
    private val avatarCleanupPending = AtomicLong()
    private val avatarCleanupOldestAgeSeconds = AtomicLong()

    init {
        ClassLoaderMetrics().bindTo(registry)
        gcMetrics.bindTo(registry)
        JvmMemoryMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
        FileDescriptorMetrics().bindTo(registry)
        UptimeMetrics().bindTo(registry)
        Gauge.builder("purr.avatar.cleanup.pending", avatarCleanupPending) { value -> value.get().toDouble() }
            .register(registry)
        Gauge.builder("purr.avatar.cleanup.oldest.age.seconds", avatarCleanupOldestAgeSeconds) { value ->
            value.get().toDouble()
        }
            .register(registry)
    }

    override fun recordUpload(
        outcome: AvatarUploadOutcome,
        inputBytes: Int,
        outputBytes: Int,
        durationNanos: Long,
    ) {
        avatarUploadCounters.getValue(outcome).increment()
        avatarInputBytes.record(inputBytes.toDouble())
        if (outputBytes > 0) avatarOutputBytes.record(outputBytes.toDouble())
        avatarUploadDuration.record(durationNanos, TimeUnit.NANOSECONDS)
    }

    override fun recordCleanup(succeeded: Boolean) {
        if (succeeded) avatarCleanupSucceeded.increment() else avatarCleanupFailed.increment()
    }

    override fun recordBacklog(pendingTasks: Long, oldestTaskAgeSeconds: Long) {
        avatarCleanupPending.set(pendingTasks)
        avatarCleanupOldestAgeSeconds.set(oldestTaskAgeSeconds)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        gcMetrics.close()
        registry.close()
    }
}
