package org.openlamp.local

enum class LightingApplyMode {
    ON_RELEASE,
    WHILE_SLIDING,
    MANUAL,
}

data class LightingValues(val warmth: Int, val brightness: Int) {
    init { require(warmth in 0..100 && brightness in 1..100) }
}

data class LightingUpdate(val values: LightingValues, val preview: Boolean)

/** One transmission at a time; a newer slider position replaces the waiting position. */
class LightingUpdates {
    var active: LightingUpdate? = null
        private set
    private var pending: LightingUpdate? = null
    val hasPending: Boolean get() = pending != null

    fun offer(update: LightingUpdate) {
        if (pending?.values == update.values && pending?.preview == false && update.preview) return
        // Returning to the in-flight value supersedes any older waiting position too.
        if (update == active) { pending = null; return }
        pending = update
    }

    fun take(): LightingUpdate? {
        if (active != null) return null
        return pending?.also { active = it; pending = null }
    }

    fun finish() { active = null }
    fun discardPending() { pending = null }
    fun clear() { active = null; pending = null }
}
