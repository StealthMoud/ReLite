package io.relite.home.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads and saves the home-screen layout (workspace items + dock). Pure
 * Kotlin + org.json only, so it's fully unit testable with a fake Storage
 * — no Context, no Robolectric.
 */
class WorkspaceRepository(
    private val storage: Storage,
    private val dockCapacity: Int = LauncherGridSpec.RMX5303.dockCapacity,
) {

    /**
     * Section 25 (v0.4.0): why the last [load] fell back to an empty
     * workspace, distinguishing "first run" (null, no prior data at all)
     * from "corrupt JSON", "schema newer than this build understands", or
     * "parsed and schema-supported but failed structural validation" —
     * rather than every one of those cases looking identical to a caller.
     */
    var lastLoadIssue: String? = null
        private set

    /**
     * True when the last [load] found **no layout file at all** — a genuine
     * first run, as opposed to a file that existed and happened to contain
     * an empty workspace.
     *
     * That distinction is what lets [DefaultLayout] seed a starting layout
     * exactly once: a user who deliberately clears their home screen has a
     * persisted (empty) file, so this stays false and their choice is
     * respected. A corrupt file is also not a first run — it is preserved
     * by [Storage.backupCorrupt] and reported via [lastLoadIssue].
     */
    var lastLoadWasFirstRun: Boolean = false
        private set

    fun load(): Workspace {
        lastLoadIssue = null
        lastLoadWasFirstRun = false
        val raw = storage.read() ?: run {
            lastLoadWasFirstRun = true
            return Workspace.empty() // no prior data — genuinely a first run
        }
        return try {
            val workspace = deserialize(raw)
            val problems = validate(workspace, LauncherGridSpec.forGrid(workspace.homeGrid, dockCapacity))
            if (problems.isNotEmpty()) {
                lastLoadIssue = "structural validation failed: ${problems.joinToString("; ")}"
                storage.backupCorrupt(raw)
                Workspace.empty()
            } else {
                workspace
            }
        } catch (e: UnsupportedSchemaException) {
            lastLoadIssue = e.message
            storage.backupCorrupt(raw)
            Workspace.empty()
        } catch (e: Exception) {
            // Corrupt or malformed layout file: fail safe to an empty
            // *in-memory* workspace rather than crashing the launcher on
            // startup — but preserve the actual bad content on disk
            // first (section 50) so it isn't silently destroyed the
            // moment any edit triggers a save() with the empty result.
            lastLoadIssue = "corrupt: ${e.message}"
            storage.backupCorrupt(raw)
            Workspace.empty()
        }
    }

    /**
     * Returns true once the workspace is durably persisted — see
     * [Storage.write]. Section 26 (v0.5.0): validates [workspace] itself
     * before writing, refusing to persist a structurally invalid candidate
     * even if a caller other than [WorkspaceController] ever calls this
     * directly — [WorkspaceController.mutate] already only builds valid
     * candidates, so this is a defense-in-depth boundary check, not a path
     * expected to actually reject anything in normal operation.
     */
    fun save(workspace: Workspace): Boolean {
        if (validate(workspace, LauncherGridSpec.forGrid(workspace.homeGrid, dockCapacity)).isNotEmpty()) return false
        return storage.write(serialize(workspace))
    }

    /**
     * Section 60/65-66: a portable layout for export/import. Apps, folders
     * and the dock serialize exactly as in the internal file; widgets are
     * carried as device-independent [PortableWidget] descriptors under a
     * separate top-level `"widgets"` key instead of being dropped, which is
     * all earlier versions could do.
     *
     * The descriptor deliberately omits `appWidgetId` — see [PortableWidget]
     * for why that number is the one part of a widget that cannot travel.
     *
     * **The internal schema is genuinely unchanged at 3, not silently
     * bumped.** `"widgets"` is written only into an *export*, never into
     * the internal workspace file (which keeps storing widgets inline, with
     * their live `appWidgetId`, exactly as before). The key is additive and
     * strictly ignorable, so an *older* ReLite build reading a newer export
     * degrades to precisely its existing behavior — layout imports, widgets
     * are dropped — rather than rejecting the entire file over a schema
     * number it doesn't recognize. Bumping would have bought nothing and
     * cost that graceful degradation.
     */
    fun exportPortable(workspace: Workspace): String {
        val withoutWidgets = workspace.copy(items = workspace.items.filterNot { it is WorkspaceItem.WidgetIcon })
        val root = JSONObject(serialize(withoutWidgets))
        val widgets = JSONArray()
        workspace.items.filterIsInstance<WorkspaceItem.WidgetIcon>()
            // A widget whose provider was never recorded (a schema-1 file
            // that predates providerComponent, see itemFromJson) has nothing
            // portable to describe — carrying a blank provider would just
            // produce an unrestorable descriptor on the other side.
            .filter { it.providerComponent.isNotEmpty() }
            .forEach { widget ->
                widgets.put(
                    JSONObject()
                        .put("providerComponent", widget.providerComponent)
                        .put("position", positionToJson(widget.position))
                        .put("spanColumns", widget.spanColumns)
                        .put("spanRows", widget.spanRows),
                )
            }
        root.put("widgets", widgets)
        return root.toString()
    }

    /**
     * Parses the `"widgets"` key [exportPortable] writes. An export from
     * any earlier version has no such key at all and yields an empty list,
     * which is exactly the old "import carries no widgets" behavior.
     *
     * Malformed individual descriptors are skipped rather than failing the
     * whole import: a layout file's apps and folders are still worth
     * restoring even if one widget entry is unreadable.
     */
    internal fun parsePortableWidgets(raw: String): List<PortableWidget> {
        val array = runCatching { JSONObject(raw).optJSONArray("widgets") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val json = array.getJSONObject(i)
                val provider = json.getString("providerComponent")
                if (!COMPONENT_KEY_RE.matches(provider)) return@runCatching null
                PortableWidget(
                    providerComponent = provider,
                    position = positionFromJson(json.getJSONObject("position")),
                    spanColumns = json.getInt("spanColumns"),
                    spanRows = json.getInt("spanRows"),
                )
            }.getOrNull()
        }
    }

    /**
     * Section 63-65: parses and structurally validates an imported layout,
     * then drops any app/folder-member reference to a component that isn't
     * currently installed (reported back via [ImportResult.Success.missingApps]
     * rather than silently creating a dead shortcut). Never mutates anything
     * — the caller decides whether/when to actually commit the result.
     */
    fun importPortable(raw: String, installedComponentKeys: Set<String>): ImportResult {
        val parsed = try {
            deserialize(raw)
        } catch (e: Exception) {
            return ImportResult.Failure("could not parse layout: ${e.message}")
        }
        val problems = validate(parsed, LauncherGridSpec.forGrid(parsed.homeGrid, dockCapacity))
        if (problems.isNotEmpty()) {
            return ImportResult.Failure(problems.joinToString("; "))
        }

        val missing = mutableSetOf<String>()
        val filteredItems = parsed.items.mapNotNull { item ->
            when (item) {
                is WorkspaceItem.AppIcon ->
                    if (item.componentKey in installedComponentKeys) item else { missing += item.componentKey; null }
                is WorkspaceItem.FolderIcon -> {
                    val (present, absent) = item.itemComponentKeys.partition { it in installedComponentKeys }
                    missing += absent
                    item.copy(itemComponentKeys = present)
                }
                is WorkspaceItem.WidgetIcon -> null // never present in a portable export, but guard defensively anyway
            }
        }
        val filteredDock = parsed.dockComponentKeys.filter { key ->
            (key in installedComponentKeys).also { if (!it) missing += key }
        }

        return ImportResult.Success(
            candidate = parsed.copy(items = filteredItems, dockComponentKeys = filteredDock),
            missingApps = missing.toList(),
            // Section 65-66 (v0.5.0 completion pass): widget descriptors are
            // returned *alongside* the candidate workspace rather than inside
            // it, because they are not yet WorkspaceItems and deliberately
            // cannot be: a WidgetIcon requires a live appWidgetId (validate()
            // rejects any that lacks one), and no id exists until the caller
            // actually re-binds the provider on this device. Committing the
            // candidate and restoring widgets are therefore two separate
            // steps — see io.relite.home.ui.widget.WidgetRestorer.
            pendingWidgets = parsePortableWidgets(raw),
        )
    }

    sealed class ImportResult {
        data class Success(
            val candidate: Workspace,
            val missingApps: List<String>,
            val pendingWidgets: List<PortableWidget> = emptyList(),
        ) : ImportResult()
        data class Failure(val reason: String) : ImportResult()
    }

    private class UnsupportedSchemaException(message: String) : Exception(message)

    internal fun serialize(workspace: Workspace): String {
        val root = JSONObject()
        root.put("schema", SCHEMA_VERSION)
        root.put("pageCount", workspace.pageCount)
        root.put("dock", JSONArray(workspace.dockComponentKeys))
        root.put("homeGrid", workspace.homeGrid.name)
        root.put("defaultPage", workspace.defaultPage)

        val items = JSONArray()
        for (item in workspace.items) {
            items.put(itemToJson(item))
        }
        root.put("items", items)
        return root.toString()
    }

    internal fun deserialize(raw: String): Workspace {
        val root = JSONObject(raw)
        val schema = root.optInt("schema", 1)
        if (schema !in SUPPORTED_SCHEMAS) {
            throw UnsupportedSchemaException("unsupported workspace schema $schema (supported: $SUPPORTED_SCHEMAS)")
        }

        val dock = mutableListOf<String>()
        val dockArray = root.optJSONArray("dock") ?: JSONArray()
        for (i in 0 until dockArray.length()) dock.add(dockArray.getString(i))

        val items = mutableListOf<WorkspaceItem>()
        val itemsArray = root.optJSONArray("items") ?: JSONArray()
        for (i in 0 until itemsArray.length()) {
            items.add(itemFromJson(itemsArray.getJSONObject(i)))
        }

        // Section 56 (v0.5.0): a schema-1/2 file predates homeGrid entirely
        // and was always the fixed 4x5 grid; FOUR_BY_SIX (4x6) is a strict
        // superset of that (same columns, one more row), so this migration
        // never needs to move an existing item — see LauncherGridSpec.RMX5303's
        // kdoc.
        val homeGrid = runCatching { HomeGridPreset.valueOf(root.optString("homeGrid", "")) }
            .getOrDefault(HomeGridPreset.FOUR_BY_SIX)

        val pageCount = root.optInt("pageCount", 1)
        return Workspace(
            pageCount = pageCount,
            items = items,
            dockComponentKeys = dock,
            homeGrid = homeGrid,
            defaultPage = root.optInt("defaultPage", 0).coerceIn(0, maxOf(0, pageCount - 1)),
        )
    }

    private fun positionToJson(position: GridPosition): JSONObject =
        JSONObject().put("page", position.page).put("column", position.column).put("row", position.row)

    private fun positionFromJson(json: JSONObject): GridPosition =
        GridPosition(json.getInt("page"), json.getInt("column"), json.getInt("row"))

    private fun itemToJson(item: WorkspaceItem): JSONObject {
        val json = JSONObject()
        json.put("id", item.id)
        json.put("position", positionToJson(item.position))
        when (item) {
            is WorkspaceItem.AppIcon -> {
                json.put("type", TYPE_APP)
                json.put("componentKey", item.componentKey)
            }
            is WorkspaceItem.FolderIcon -> {
                json.put("type", TYPE_FOLDER)
                json.put("label", item.label)
                json.put("itemComponentKeys", JSONArray(item.itemComponentKeys))
                json.put("folderSize", item.size.name)
            }
            is WorkspaceItem.WidgetIcon -> {
                json.put("type", TYPE_WIDGET)
                json.put("appWidgetId", item.appWidgetId)
                json.put("spanColumns", item.spanColumns)
                json.put("spanRows", item.spanRows)
                json.put("providerComponent", item.providerComponent)
            }
        }
        return json
    }

    private fun itemFromJson(json: JSONObject): WorkspaceItem {
        val id = json.getString("id")
        val position = positionFromJson(json.getJSONObject("position"))
        return when (val type = json.getString("type")) {
            TYPE_APP -> WorkspaceItem.AppIcon(id, position, json.getString("componentKey"))
            TYPE_FOLDER -> {
                val keysArray = json.optJSONArray("itemComponentKeys") ?: JSONArray()
                val keys = (0 until keysArray.length()).map { keysArray.getString(it) }
                // Section 26 (v0.5.0 completion pass): a workspace saved
                // before this field existed has no "folderSize" key at all —
                // every such folder migrates in as COMPACT, its only
                // possible footprint before this pass.
                val size = runCatching { FolderSize.valueOf(json.optString("folderSize", FolderSize.COMPACT.name)) }
                    .getOrDefault(FolderSize.COMPACT)
                WorkspaceItem.FolderIcon(id, position, json.getString("label"), keys, size)
            }
            TYPE_WIDGET -> WorkspaceItem.WidgetIcon(
                id, position,
                json.getInt("appWidgetId"), json.getInt("spanColumns"), json.getInt("spanRows"),
                // Section 22: a schema-1 file (pre-dating providerComponent)
                // migrates in-place rather than being rejected — the field
                // is simply unknown until the widget is next re-added.
                providerComponent = json.optString("providerComponent", ""),
            )
            else -> throw IllegalArgumentException("unknown workspace item type: $type")
        }
    }

    companion object {
        // Section 55 (v0.5.0): schema 3 adds Workspace.homeGrid. Schemas 1
        // and 2 remain readable — they simply predate the field and default
        // to FOUR_BY_SIX on load, see deserialize().
        private const val SCHEMA_VERSION = 3
        private val SUPPORTED_SCHEMAS = setOf(1, 2, 3)
        private const val TYPE_APP = "app"
        private const val TYPE_FOLDER = "folder"
        private const val TYPE_WIDGET = "widget"

        private val COMPONENT_KEY_RE = Regex("^[a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+$")

        /**
         * Section 23: a deserialized workspace is only trusted after
         * structural validation, not merely because the JSON parsed.
         * Returns a human-readable list of every problem found (empty =
         * valid) rather than stopping at the first one, so a single bad
         * layout file's diagnostic message is actually useful.
         */
        internal fun validate(workspace: Workspace, spec: LauncherGridSpec): List<String> {
            val problems = mutableListOf<String>()

            if (workspace.pageCount < 1) problems += "pageCount ${workspace.pageCount} must be >= 1"

            val seenIds = mutableSetOf<String>()
            for (item in workspace.items) {
                if (!seenIds.add(item.id)) problems += "duplicate item id '${item.id}'"

                val rect = GridRect.of(item)
                if (!rect.isWithinBounds(spec, workspace.pageCount)) {
                    problems += "item '${item.id}' rect $rect is out of bounds for $spec / pageCount=${workspace.pageCount}"
                }

                when (item) {
                    is WorkspaceItem.AppIcon ->
                        if (!COMPONENT_KEY_RE.matches(item.componentKey)) {
                            problems += "item '${item.id}' has malformed componentKey '${item.componentKey}'"
                        }
                    is WorkspaceItem.FolderIcon ->
                        for (key in item.itemComponentKeys) {
                            if (!COMPONENT_KEY_RE.matches(key)) {
                                problems += "folder '${item.id}' has malformed member componentKey '$key'"
                            }
                        }
                    is WorkspaceItem.WidgetIcon -> {
                        if (item.providerComponent.isNotEmpty() && !COMPONENT_KEY_RE.matches(item.providerComponent)) {
                            problems += "widget '${item.id}' has malformed providerComponent '${item.providerComponent}'"
                        }
                        if (item.appWidgetId <= 0) problems += "widget '${item.id}' has invalid appWidgetId ${item.appWidgetId}"
                    }
                }
            }

            // Rectangle-overlap check (section 20): every pair, not just
            // exact single-cell equality — O(n^2) but n is a handful of
            // items per device, never a performance concern.
            val rects = workspace.items.map { it.id to GridRect.of(it) }
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    if (rects[i].second.intersects(rects[j].second)) {
                        problems += "items '${rects[i].first}' and '${rects[j].first}' overlap"
                    }
                }
            }

            if (workspace.dockComponentKeys.size > spec.dockCapacity) {
                problems += "dock has ${workspace.dockComponentKeys.size} entries, capacity is ${spec.dockCapacity}"
            }
            if (workspace.dockComponentKeys.toSet().size != workspace.dockComponentKeys.size) {
                problems += "dock has duplicate entries"
            }
            for (key in workspace.dockComponentKeys) {
                if (!COMPONENT_KEY_RE.matches(key)) problems += "dock has malformed componentKey '$key'"
            }

            return problems
        }
    }
}
