package io.relite.home.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads and saves the home-screen layout (workspace items + dock). Pure
 * Kotlin + org.json only, so it's fully unit testable with a fake Storage
 * — no Context, no Robolectric.
 */
class WorkspaceRepository(private val storage: Storage) {

    fun load(): Workspace {
        val raw = storage.read() ?: return Workspace.empty()
        return try {
            deserialize(raw)
        } catch (_: Exception) {
            // Corrupt or foreign-schema layout file: fail safe to an empty
            // workspace rather than crashing the launcher on startup.
            Workspace.empty()
        }
    }

    fun save(workspace: Workspace) {
        storage.write(serialize(workspace))
    }

    internal fun serialize(workspace: Workspace): String {
        val root = JSONObject()
        root.put("schema", SCHEMA_VERSION)
        root.put("pageCount", workspace.pageCount)
        root.put("dock", JSONArray(workspace.dockComponentKeys))

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
        require(schema == SCHEMA_VERSION) { "unsupported workspace schema $schema" }

        val dock = mutableListOf<String>()
        val dockArray = root.optJSONArray("dock") ?: JSONArray()
        for (i in 0 until dockArray.length()) dock.add(dockArray.getString(i))

        val items = mutableListOf<WorkspaceItem>()
        val itemsArray = root.optJSONArray("items") ?: JSONArray()
        for (i in 0 until itemsArray.length()) {
            items.add(itemFromJson(itemsArray.getJSONObject(i)))
        }

        return Workspace(
            pageCount = root.optInt("pageCount", 1),
            items = items,
            dockComponentKeys = dock,
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
            }
            is WorkspaceItem.WidgetIcon -> {
                json.put("type", TYPE_WIDGET)
                json.put("appWidgetId", item.appWidgetId)
                json.put("spanColumns", item.spanColumns)
                json.put("spanRows", item.spanRows)
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
                WorkspaceItem.FolderIcon(id, position, json.getString("label"), keys)
            }
            TYPE_WIDGET -> WorkspaceItem.WidgetIcon(
                id, position,
                json.getInt("appWidgetId"), json.getInt("spanColumns"), json.getInt("spanRows"),
            )
            else -> throw IllegalArgumentException("unknown workspace item type: $type")
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val TYPE_APP = "app"
        private const val TYPE_FOLDER = "folder"
        private const val TYPE_WIDGET = "widget"
    }
}
