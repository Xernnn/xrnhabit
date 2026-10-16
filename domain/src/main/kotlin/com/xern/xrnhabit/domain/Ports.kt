package com.xern.xrnhabit.domain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ports the domain defines and `:app` implements. See `docs/domain-contract.md` §4.
 * The engine itself never calls [ShieldPort]; it returns an [Outcome] and the app layer
 * persists first, then applies the shield ([applyOutcome]).
 */

interface Clock {
    /** `SystemClock.elapsedRealtime()` on Android. Not affected by the user setting the clock. */
    fun nowMonotonicMs(): Long

    /** `System.currentTimeMillis()` on Android. User-settable. */
    fun nowWallMs(): Long

    fun zone(): ZoneId
}

fun Clock.localDate(): LocalDate = Instant.ofEpochMilli(nowWallMs()).atZone(zone()).toLocalDate()

fun Clock.epochDay(): Long = localDate().toEpochDay()

fun Clock.startOfLocalDayMs(): Long =
    localDate().atStartOfDay(zone()).toInstant().toEpochMilli()

interface PersistPort {
    fun loadWorld(): World

    /** Must be atomic and must return before the caller touches the shield (I7). */
    fun save(world: World)
}

interface ShieldPort {
    fun apply(plan: ShieldPlan)
}

interface Telemetry {
    fun onBlockedImpression(pkg: String, atWallMs: Long)
    fun onHabitCompleted(blockId: String, atWallMs: Long)
    /** [startedAtWall] names the session (I28): devices sharing it each log it, under one name. */
    fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long, startedAtWall: Long)
}

/**
 * The full desired shield state. Applying it is idempotent. [blockedSites] holds target domains;
 * a host is covered when it [Sites.matches] any of them (I18).
 */
@Serializable
data class ShieldPlan(val blockedPackages: Set<String>, val blockedSites: Set<String> = emptySet()) {
    fun covers(pkg: String?, site: String?): Boolean =
        (pkg != null && pkg in blockedPackages) ||
            (site != null && blockedSites.any { Sites.matches(site, it) })
}

enum class RejectReason { NOT_EARNED, CAP_REACHED }

sealed interface Effect {
    data class BlockedImpression(val pkg: String) : Effect
    data class HabitCompleted(val blockId: String) : Effect
    data class UnlockConsumed(val blockId: String, val ms: Long, val startedAtWall: Long = 0) : Effect
    data class UnlockEndingSoon(val blockId: String, val remainingMs: Long) : Effect
    data class TamperDetected(val blockId: String) : Effect
    data class RedeemRejected(val blockId: String, val reason: RejectReason) : Effect
}

/**
 * Result of [BlockEngine.reduce]. [shieldPlan] is non-null only when the desired blocked
 * set changed (or on cold start) — see contract §4.
 */
data class Outcome(
    val world: World,
    val shieldPlan: ShieldPlan?,
    val effects: List<Effect>,
)

/**
 * Canonical order for applying an [Outcome]: persist, then shield, then side effects.
 * This ordering is invariant I7 and is exercised by `i07_persistHappensBeforeShield`.
 */
fun applyOutcome(
    outcome: Outcome,
    persist: PersistPort,
    shield: ShieldPort,
    telemetry: Telemetry,
    nowWallMs: Long,
) {
    persist.save(outcome.world)
    outcome.shieldPlan?.let(shield::apply)
    outcome.effects.forEach { effect ->
        when (effect) {
            is Effect.BlockedImpression -> telemetry.onBlockedImpression(effect.pkg, nowWallMs)
            is Effect.HabitCompleted -> telemetry.onHabitCompleted(effect.blockId, nowWallMs)
            is Effect.UnlockConsumed -> telemetry.onUnlockConsumed(effect.blockId, effect.ms, nowWallMs, effect.startedAtWall)
            is Effect.UnlockEndingSoon,
            is Effect.TamperDetected,
            is Effect.RedeemRejected -> Unit // routed to notifications / UI by the caller
        }
    }
}
