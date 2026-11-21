package gitviz.platform

import java.util.prefs.Preferences

/**
 * 应用偏好设置所在的节点。改名前叫 gitviz：第一次打开新节点时把旧节点里的值拷过来，
 * 旧节点保留不删，回去用旧版本也不受影响。
 */
internal object AppPreferences {
    const val NODE = "preflight"
    const val LEGACY_NODE = "gitviz"

    val node: Preferences by lazy { open(Preferences.userRoot()) }

    internal fun open(root: Preferences, name: String = NODE, legacy: String = LEGACY_NODE): Preferences {
        val fresh = !root.nodeExists(name)
        val node = root.node(name)
        if (fresh && root.nodeExists(legacy)) {
            val old = root.node(legacy)
            for (key in old.keys()) old.get(key, null)?.let { node.put(key, it) }
            runCatching { node.flush() }
        }
        return node
    }
}
