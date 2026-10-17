package com.xern.xrnhabit.app.data

import com.xern.xrnhabit.domain.Block
import com.xern.xrnhabit.domain.BlockState
import com.xern.xrnhabit.domain.ChecklistItem
import com.xern.xrnhabit.domain.Settings
import com.xern.xrnhabit.domain.World
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Blocks exported to a file, so a reinstall or a new phone doesn't mean building them again
 * (backups are off for xrnhabit). Only the setup travels: no progress, credits or sessions.
 */
@Serializable
data class BlocksBackup(
    val version: Int = 1,
    val blocks: List<Block>,
    val checklist: Map<String, List<ChecklistItem>> = emptyMap(),
    val settings: Settings = Settings(),
)

object BlocksBackups {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun export(world: World): String = json.encodeToString(
        BlocksBackup.serializer(),
        BlocksBackup(
            blocks = world.blocks.map { it.copy(state = BlockState.ARMED) },
            checklist = world.checklist.mapValues { (_, items) -> items.map(::fresh) },
            settings = world.settings,
        ),
    )

    /**
     * Adds the backup's blocks that aren't here yet, fresh and locked. Importing never loosens
     * anything (contract §2.3): existing blocks are left alone and the daily unlock limit can
     * only go down. Throws on a file that isn't a backup.
     */
    fun import(world: World, text: String): Pair<World, Int> {
        val backup = json.decodeFromString(BlocksBackup.serializer(), text)
        val existing = world.blocks.mapTo(HashSet()) { it.id }
        val added = backup.blocks.filter { it.id !in existing }.map { it.copy(state = BlockState.ARMED) }
        val checklist = added.associate { b -> b.id to backup.checklist[b.id].orEmpty().map(::fresh) }
            .filterValues { it.isNotEmpty() }
        val cap = minOf(world.settings.dailyCapMs, backup.settings.dailyCapMs)
        val imported = world.copy(
            blocks = world.blocks + added,
            checklist = world.checklist + checklist,
            settings = world.settings.copy(dailyCapMs = cap),
        )
        return imported to added.size
    }

    private fun fresh(item: ChecklistItem) = item.copy(done = false, doneAtMonotonic = null, spentEpochDay = null)
}
