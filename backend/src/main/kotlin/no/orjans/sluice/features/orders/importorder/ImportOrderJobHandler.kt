package no.orjans.sluice.features.orders.importorder

import no.orjans.sluice.features.jobs.JobContext
import no.orjans.sluice.features.jobs.JobEffects
import no.orjans.sluice.features.jobs.JobHandler
import no.orjans.sluice.features.jobs.JobOutcome
import no.orjans.sluice.features.jobs.JobType
import no.orjans.sluice.features.orders.ErpOrder
import no.orjans.sluice.features.orders.ErpOrderSender
import no.orjans.sluice.features.orders.ErpSendResult
import no.orjans.sluice.features.orders.ImportOrderPayload
import no.orjans.sluice.features.orders.OrderId
import no.orjans.sluice.features.orders.OrderImport
import no.orjans.sluice.features.orders.OrderStatus
import org.springframework.stereotype.Component

/**
 * Sends a received order to the ERP and records, per line, what was sent and what came back.
 *
 * Runs without a transaction (ADR 0010): it only reads and calls the ERP; every write is in the
 * returned effects. It can run more than once for the same order (crash, expired lease); the
 * ERP's idempotency key (the order ID) makes the repeat harmless.
 *
 * | Situation                               | Outcome   | Order    |
 * |-----------------------------------------|-----------|----------|
 * | Already imported                        | Succeeded | unchanged |
 * | A line can't be mapped (missing SKU, …) | Permanent | REJECTED, nothing sent |
 * | ERP accepts (or replays an earlier yes) | Succeeded | IMPORTED + ERP order number |
 * | ERP rejects (4xx, e.g. unknown SKU)     | Permanent | REJECTED |
 * | ERP unavailable (timeout, 5xx, 408/409/429) | Temporary | unchanged, retried (429: after Retry-After) |
 */
@Component
class ImportOrderJobHandler(
    private val reader: OrderForImportReader,
    private val mapper: ErpOrderMapper,
    private val erp: ErpOrderSender,
    private val recorder: ImportRecorder,
) : JobHandler<ImportOrderPayload> {
    override val type: JobType = OrderImport.JOB_TYPE
    override val payloadType: Class<ImportOrderPayload> = ImportOrderPayload::class.java

    override fun handle(
        payload: ImportOrderPayload,
        context: JobContext,
    ): JobOutcome {
        val order = reader.find(payload.orderId)
        return when {
            order == null -> {
                JobOutcome.PermanentFailure("Order ${payload.orderId} not found")
            }

            order.status == OrderStatus.IMPORTED -> {
                JobOutcome.Succeeded()
            }

            else -> {
                when (val mapping = mapper.map(order)) {
                    is ErpMapping.Invalid -> invalid(order, mapping, context)
                    is ErpMapping.Ready -> send(mapping.order, context)
                }
            }
        }
    }

    private fun invalid(
        order: OrderForImport,
        mapping: ErpMapping.Invalid,
        context: JobContext,
    ): JobOutcome {
        val problemsByLine = mapping.problems.associateBy { it.lineNumber }
        val entries =
            order.lines.mapNotNull { line ->
                problemsByLine[line.lineNumber]?.let {
                    LineLogEntry(
                        line.lineNumber,
                        LineOutcome.INVALID,
                        errorCode = it.code,
                        errorMessage = it.message,
                    )
                }
            }
        val reason =
            if (mapping.problems.isEmpty()) {
                "The order has no lines"
            } else {
                "Not sent to the ERP: " + mapping.problems.joinToString("; ") { "line ${it.lineNumber} ${it.code}" }
            }
        return JobOutcome.PermanentFailure(reason, effects(order.id, context, entries, rejected = true))
    }

    private fun send(
        order: ErpOrder,
        context: JobContext,
    ): JobOutcome =
        when (val result = erp.send(order)) {
            is ErpSendResult.Accepted -> {
                val confirmations = result.lines.associateBy { it.lineNumber }
                val entries =
                    order.lines.map {
                        LineLogEntry(
                            it.lineNumber,
                            LineOutcome.ACCEPTED,
                            sent = it,
                            received = confirmations[it.lineNumber],
                        )
                    }
                JobOutcome.Succeeded {
                    recorder.logLines(order.orderReference, context, entries)
                    recorder.markImported(order.orderReference, result.erpOrderNumber)
                }
            }

            is ErpSendResult.Rejected -> {
                val errorsByLine = result.errors.filter { it.lineNumber != null }.associateBy { it.lineNumber }
                val entries =
                    order.lines.map { line ->
                        val error = errorsByLine[line.lineNumber]
                        if (error == null) {
                            LineLogEntry(line.lineNumber, LineOutcome.ORDER_REJECTED, sent = line)
                        } else {
                            LineLogEntry(line.lineNumber, LineOutcome.REJECTED, line, error, error.code, error.message)
                        }
                    }
                val reason =
                    "Rejected by the ERP: " + result.errors.joinToString("; ") { e -> e.lineNumber?.let { "line $it ${e.code}" } ?: e.code }
                JobOutcome.PermanentFailure(reason, effects(order.orderReference, context, entries, rejected = true))
            }

            is ErpSendResult.Unavailable -> {
                val entries =
                    order.lines.map {
                        LineLogEntry(
                            it.lineNumber,
                            LineOutcome.NO_RESPONSE,
                            sent = it,
                            errorCode = ERP_UNAVAILABLE,
                            errorMessage = result.reason,
                        )
                    }
                JobOutcome.TemporaryFailure(
                    "ERP unavailable: ${result.reason}",
                    effects(order.orderReference, context, entries, rejected = false),
                    retryAfter = result.retryAfter,
                )
            }
        }

    private fun effects(
        orderId: OrderId,
        context: JobContext,
        entries: List<LineLogEntry>,
        rejected: Boolean,
    ) = JobEffects {
        recorder.logLines(orderId, context, entries)
        if (rejected) recorder.markRejected(orderId)
    }

    private companion object {
        const val ERP_UNAVAILABLE = "ERP_UNAVAILABLE"
    }
}
