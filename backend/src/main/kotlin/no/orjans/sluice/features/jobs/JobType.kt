package no.orjans.sluice.features.jobs

/**
 * Names a kind of job, e.g. `order.import`. Each [JobHandler] declares the type it handles, so a new
 * job type is a new handler class, never a new branch in the worker (open/closed).
 *
 * Not an enum: the worker must not need to know every type that exists.
 */
@JvmInline
value class JobType(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "Job type must be lower-case dot-separated, e.g. 'order.import', was '$value'" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*$")
    }
}
