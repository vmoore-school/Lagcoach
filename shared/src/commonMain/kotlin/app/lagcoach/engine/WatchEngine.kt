package app.lagcoach.engine

import app.lagcoach.model.Departure
import app.lagcoach.model.Observation
import app.lagcoach.model.Watch
import app.lagcoach.model.roundHalfAway
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

enum class AlertKind { Cancelled, Late, IntoWindow, Early }

data class Alert(
    val kind: AlertKind,
    val watch: Watch,
    val departure: Departure,
    val delayMinutes: Int,
) {
    /** One notification per bus; later alerts about the same bus replace it. */
    val notificationKey: String get() = "${watch.id}|${departure.key}"

    val title: String
        get() {
            val d = departure
            val at = Schedule.hhmm(d.scheduled)
            return when (kind) {
                AlertKind.Cancelled -> "${d.line} at $at cancelled"
                AlertKind.Late -> "${d.line} at $at running $delayMinutes min late"
                AlertKind.IntoWindow -> "Late ${d.line} now due ${Schedule.hhmm(d.effective)}"
                AlertKind.Early -> "${d.line} at $at running ${-delayMinutes} min early"
            }
        }

    val body: String
        get() {
            val d = departure
            val stop = watch.stop.name
            return when (kind) {
                AlertKind.Cancelled -> "To ${d.destination} from $stop. ${watch.label}"
                AlertKind.Late -> "Now expected ${Schedule.hhmm(d.effective)} at $stop, to ${d.destination}."
                AlertKind.IntoWindow ->
                    "The ${Schedule.hhmm(d.scheduled)} to ${d.destination} is $delayMinutes min late, " +
                        "so it now reaches $stop inside your ${watch.label.lowercase()} window."
                AlertKind.Early -> "Now expected ${Schedule.hhmm(d.effective)} at $stop. Leave a little earlier."
            }
        }
}

data class EvaluationResult(
    val alerts: List<Alert>,
    val observations: List<Observation>,
    /** Departures relevant to the window, soonest first, for the ongoing notification / UI. */
    val relevant: List<Departure>,
)

/**
 * Pure decision logic: given what the departure board says now and what we've already
 * recorded/notified, decide which alerts to raise. No I/O, so it's unit tested directly.
 */
object WatchEngine {
    /** How far before the window a bus can be scheduled and still be "late into" it. */
    val intoWindowLookback = 90.minutes
    /** Re-alert a late bus when its delay grows by this much since the last alert. */
    const val LATE_STEP_MIN = 5

    fun evaluate(
        watch: Watch,
        window: WindowSpan,
        departures: List<Departure>,
        now: Instant,
        existing: Map<String, Observation>,
        travelMinutes: (Departure) -> Int? = { null },
    ): EvaluationResult {
        val prefs = watch.alerts
        val alerts = mutableListOf<Alert>()
        val obs = mutableListOf<Observation>()
        val relevant = mutableListOf<Departure>()

        for (d in departures.distinctBy { it.key }) {
            if (!watch.matchesLine(d.line)) continue
            val inWindow = d.scheduled in window
            val intoWindow = !inWindow &&
                d.scheduled < window.start && d.scheduled >= window.start - intoWindowLookback &&
                !d.cancelled && d.effective in window
            if (!inWindow && !intoWindow) continue
            relevant += d

            val key = "${watch.id}|${d.line}|${d.scheduled.epochSeconds}"
            val prev = existing[key]
            val delay = d.delaySeconds?.let { roundHalfAway(it / 60.0) } ?: 0
            var o = (prev ?: Observation(
                watchId = watch.id,
                date = window.date.toString(),
                line = d.line,
                destination = d.destination,
                scheduled = d.scheduled.epochSeconds,
            )).copy(
                expected = d.expected?.epochSeconds ?: prev?.expected,
                cancelled = d.cancelled,
                inWindow = inWindow,
                lastSeen = now.epochSeconds,
                travelMinutes = prev?.travelMinutes ?: travelMinutes(d),
            )

            // Don't nag about buses that have already gone.
            val upcoming = d.effective >= now - 1.minutes || (d.cancelled && d.scheduled >= now - 1.minutes)
            if (upcoming) {
                if (d.cancelled) {
                    if (inWindow && prefs.cancellations && !o.notifiedCancelled) {
                        alerts += Alert(AlertKind.Cancelled, watch, d, 0)
                        o = o.copy(notifiedCancelled = true)
                    }
                } else if (intoWindow) {
                    if (prefs.lateIntoWindow && !o.notifiedIntoWindow && delay >= prefs.lateThresholdMin) {
                        alerts += Alert(AlertKind.IntoWindow, watch, d, delay)
                        o = o.copy(notifiedIntoWindow = true)
                    }
                } else if (delay >= prefs.lateThresholdMin) {
                    val last = o.notifiedLateMin
                    if (last == null || delay >= last + LATE_STEP_MIN) {
                        alerts += Alert(AlertKind.Late, watch, d, delay)
                        o = o.copy(notifiedLateMin = delay)
                    }
                } else if (prefs.runningEarly && delay <= -prefs.earlyThresholdMin && !o.notifiedEarly) {
                    alerts += Alert(AlertKind.Early, watch, d, delay)
                    o = o.copy(notifiedEarly = true)
                }
            }
            if (o != prev) obs += o
        }
        return EvaluationResult(alerts, obs, relevant.sortedBy { it.effective })
    }

    /** One-line status for a bus, e.g. "On time", "6 min late", "Cancelled". */
    fun status(d: Departure): String {
        if (d.cancelled) return "Cancelled"
        val delay = d.delaySeconds?.let { roundHalfAway(it / 60.0) } ?: return "No live times yet"
        return when {
            delay >= 2 -> "$delay min late"
            delay <= -2 -> "${-delay} min early"
            else -> "On time"
        }
    }

    fun summary(watch: Watch, relevant: List<Departure>, now: Instant): String {
        val next = relevant.firstOrNull { it.effective >= now - 1.minutes && !it.cancelled }
            ?: return "No more ${if (watch.lines.isEmpty()) "buses" else watch.lines.joinToString("/")} in this window"
        val time = Schedule.hhmm(next.effective)
        return "Next ${next.line} $time · ${status(next).lowercase().replaceFirstChar { it.uppercase() }}"
    }
}
