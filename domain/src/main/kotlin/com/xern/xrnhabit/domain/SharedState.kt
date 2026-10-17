package com.xern.xrnhabit.domain

import kotlinx.serialization.Serializable

/**
 * The part of a [World] every device shares through the sync server (contract §2.4): what is
 * configured, earned, open, locked down or paused, and how much of today's limits is used.
 *
 * Only wall-clock times cross devices. [of] zeroes every monotonic stamp and
 * [BlockEvent.SharedStateMerged] anchors them again on arrival, as a cold start does (I8).
 * Not shared: a linked item's `done` (each device reads its own tracker), HABIT timer
 * progress, the foreground, the applied shield.
 */
@Serializable
data class SharedState(
    /** Block configs by id, in display order; `state` is always ARMED here and derived on arrival. */
    val blocks: Map<String, Block> = emptyMap(),
    /** Every checklist item by id; linked items never carry `done` (I27). */
    val items: Map<String, ChecklistItem> = emptyMap(),
    val credits: Map<String, EarnedCredit> = emptyMap(),
    val sessions: Map<String, UnlockSession> = emptyMap(),
    val lockdowns: Map<String, Lockdown> = emptyMap(),
    val pause: Pause? = null,
    val lastPauseEpochDay: Long? = null,
    val settings: Settings = Settings(),
    /** The local day [unlockLog] belongs to (I28). */
    val unlockEpochDay: Long = 0,
    val unlockLog: Map<String, Long> = emptyMap(),
    /** ALLOWANCE use, block id -> device id -> that device's use today (I19). */
    val allowance: Map<String, Map<String, AllowanceUse>> = emptyMap(),
) {
    companion object {

        /** This device's view of the shared state. */
        fun of(world: World): SharedState {
            val allowance = world.otherAllowance.mapValues { it.value.toMutableMap() }.toMutableMap()
            if (world.deviceId.isNotEmpty()) {
                for (block in world.blocks) {
                    if (block.mode != BlockMode.ALLOWANCE) continue
                    val p = world.progress[block.id] ?: continue
                    if (p.accumulatedMs <= 0) continue
                    allowance.getOrPut(block.id) { mutableMapOf() }[world.deviceId] = AllowanceUse(p.windowStart, p.accumulatedMs)
                }
            }
            return SharedState(
                blocks = world.blocks.associate { it.id to it.copy(state = BlockState.ARMED) },
                items = world.checklist.values.flatten().associate { item ->
                    item.id to if (item.externalHabitId != null) {
                        item.copy(done = false, doneAtMonotonic = null)
                    } else {
                        item.copy(doneAtMonotonic = null)
                    }
                },
                credits = world.credits.mapValues { it.value.copy(earnedAtMonotonic = 0) },
                sessions = world.sessions.mapValues { it.value.copy(startedAtMonotonic = 0) },
                lockdowns = world.lockdowns.mapValues { it.value.copy(startedAtMonotonic = 0) },
                pause = world.pause?.copy(startedAtMonotonic = 0),
                lastPauseEpochDay = world.lastPauseEpochDay,
                settings = world.settings,
                unlockEpochDay = world.dailyUsageEpochDay,
                unlockLog = world.unlockLog,
                allowance = allowance,
            )
        }

        /**
         * I26: a three-way merge of [local] and [remote] against [base], the state both last
         * agreed on (null the first time: nothing can have been deleted yet). A key only one
         * side changed takes that side, deletion included; the table in contract §2.4 settles
         * keys both changed.
         */
        fun merge(base: SharedState?, local: SharedState, remote: SharedState): SharedState {
            val b = base ?: SharedState()
            val unlockSameDay = local.unlockEpochDay == remote.unlockEpochDay
            return SharedState(
                blocks = mergeMaps(b.blocks, local.blocks, remote.blocks) { l, r ->
                    when {
                        l == null -> r // edited beats deleted: the stricter side
                        r == null -> l
                        r.editedAtWall >= l.editedAtWall -> r
                        else -> l
                    }
                },
                items = mergeMaps(b.items, local.items, remote.items) { l, r ->
                    when {
                        l == null -> r
                        r == null -> l
                        else -> {
                            // Text, order and link settings from whichever side changed them (remote if both).
                            val old = b.items[l.id]
                            val config = if (old != null && sameConfig(r, old)) l else r
                            config.copy(
                                done = l.done || r.done,
                                spentEpochDay = listOfNotNull(l.spentEpochDay, r.spentEpochDay).maxOrNull(),
                            )
                        }
                    }
                },
                credits = mergeMaps(b.credits, local.credits, remote.credits) { l, r ->
                    when {
                        l == null || r == null -> null // spent or expired on one side
                        r.earnedAtWall < l.earnedAtWall -> r
                        else -> l
                    }
                },
                sessions = mergeMaps(b.sessions, local.sessions, remote.sessions) { l, r ->
                    when {
                        l == null -> r
                        r == null -> l
                        r.startedAtWall + r.durationMs > l.startedAtWall + l.durationMs -> r
                        else -> l
                    }
                },
                lockdowns = mergeMaps(b.lockdowns, local.lockdowns, remote.lockdowns) { l, r ->
                    when {
                        l == null -> r
                        r == null -> l
                        r.startedAtWall + r.durationMs > l.startedAtWall + l.durationMs -> r
                        else -> l
                    }
                },
                pause = mergeValue(b.pause, local.pause, remote.pause) { l, r ->
                    when {
                        l == null -> r
                        r == null -> l
                        r.startedAtWall < l.startedAtWall -> r // the first break of the day stands
                        else -> l
                    }
                },
                lastPauseEpochDay = listOfNotNull(local.lastPauseEpochDay, remote.lastPauseEpochDay).maxOrNull(),
                settings = Settings(
                    dailyCapMs = mergeValue(b.settings.dailyCapMs, local.settings.dailyCapMs, remote.settings.dailyCapMs) { l, r -> minOf(l, r) },
                    skewToleranceMs = mergeValue(b.settings.skewToleranceMs, local.settings.skewToleranceMs, remote.settings.skewToleranceMs) { l, r -> minOf(l, r) },
                    unlockWarningMs = mergeValue(b.settings.unlockWarningMs, local.settings.unlockWarningMs, remote.settings.unlockWarningMs) { l, r -> maxOf(l, r) },
                ),
                unlockEpochDay = maxOf(local.unlockEpochDay, remote.unlockEpochDay),
                unlockLog = when { // I28: entries only ever get added during a day
                    unlockSameDay -> unionMax(local.unlockLog, remote.unlockLog)
                    local.unlockEpochDay > remote.unlockEpochDay -> local.unlockLog
                    else -> remote.unlockLog
                },
                allowance = (local.allowance.keys + remote.allowance.keys).associateWith { blockId ->
                    val l = local.allowance[blockId].orEmpty()
                    val r = remote.allowance[blockId].orEmpty()
                    (l.keys + r.keys).associateWith { device ->
                        listOfNotNull(l[device], r[device]).maxWith(compareBy<AllowanceUse> { it.dayStartWall }.thenBy { it.ms })
                    }
                },
            )
        }

        /** Equal apart from the tick and the spent day, which merge on their own. */
        private fun sameConfig(a: ChecklistItem, b: ChecklistItem): Boolean =
            a.copy(done = b.done, doneAtMonotonic = b.doneAtMonotonic, spentEpochDay = b.spentEpochDay) == b

        /** Per key: unchanged on one side takes the other; changed on both goes to [resolve]. */
        private fun <V> mergeMaps(
            base: Map<String, V>,
            local: Map<String, V>,
            remote: Map<String, V>,
            resolve: (local: V?, remote: V?) -> V?,
        ): Map<String, V> {
            val out = LinkedHashMap<String, V>()
            for (key in local.keys + remote.keys + base.keys) { // local order first: it's the display order here
                mergeValue(base[key], local[key], remote[key], resolve)?.let { out[key] = it }
            }
            return out
        }

        private fun <V> mergeValue(base: V, local: V, remote: V, resolve: (V, V) -> V): V = when {
            local == remote -> local
            local == base -> remote
            remote == base -> local
            else -> resolve(local, remote)
        }

        private fun unionMax(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> =
            (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0, b[it] ?: 0) }
    }
}
