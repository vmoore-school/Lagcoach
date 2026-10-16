package app.lagcoach.engine

import app.lagcoach.model.Departure
import app.lagcoach.model.Observation
import kotlin.math.abs
import kotlin.time.Instant

/**
 * Follows buses that have left the boarding stop to the destination: the destination
 * stop's own board lists the same trip with its timetabled and live time there.
 * Pure logic, so it's unit tested; [Watcher] does the fetching.
 */
object ArrivalTracker {
    /** Departed buses whose arrival isn't settled yet. */
    fun pending(obs: Collection<Observation>, now: Instant): List<Observation> = obs.filter { o ->
        if (o.cancelled || !o.inWindow) return@filter false
        val left = (o.expected ?: o.scheduled) < now.epochSeconds - 60
        val due = o.estimatedArrival ?: o.plannedArrival ?: return@filter left
        left && due >= now.epochSeconds - 3 * 60
    }

    fun match(o: Observation, board: List<Departure>): Departure? {
        val sameLine = board.filter { it.line.equals(o.line, ignoreCase = true) }
        sameLine.firstOrNull { d ->
            (o.journeyId != null && d.journeyId == o.journeyId) || (o.tripId != null && d.tripId == o.tripId)
        }?.let { return it }
        val planned = o.plannedArrival ?: return null
        return sameLine.filter { abs(it.scheduled.epochSeconds - planned) <= 90 }.minByOrNull { abs(it.scheduled.epochSeconds - planned) }
    }

    fun update(obs: List<Observation>, board: List<Departure>, @Suppress("UNUSED_PARAMETER") now: Instant): List<Observation> = obs.mapNotNull { o ->
        val d = match(o, board) ?: return@mapNotNull null
        val next = o.copy(
            arrivalScheduled = d.scheduled.epochSeconds,
            // Keep the last live estimate once the board stops showing one (bus arrived).
            arrivalExpected = d.expected?.epochSeconds ?: o.arrivalExpected,
            travelMinutes = o.travelMinutes ?: ((d.scheduled.epochSeconds - o.scheduled) / 60).toInt(),
        )
        next.takeIf { it != o }
    }
}
