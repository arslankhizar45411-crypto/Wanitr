package com.wani.terminal

/**
 * Shortcut bar ke sab buttons ki list. Settings mein user inko chhupa ya dikha sakta hai.
 * Save sirf ID hoti hai, label yahin se aata hai.
 */
object ShortcutKeys {

    class KeyDef(val id: String, val label: String)

    val ALL: List<KeyDef> = listOf(
        KeyDef("esc", "Esc"),
        KeyDef("tab", "Tab"),
        KeyDef("ctrl", "Ctrl"),
        KeyDef("alt", "Alt"),
        KeyDef("up", "↑"),
        KeyDef("down", "↓"),
        KeyDef("left", "←"),
        KeyDef("right", "→"),
        KeyDef("enter", "↵"),
        KeyDef("ctrlc", "^C"),
        KeyDef("ctrld", "^D"),
        KeyDef("ctrlz", "^Z"),
        KeyDef("ctrll", "^L"),
        KeyDef("home", "Home"),
        KeyDef("end", "End"),
        KeyDef("pgup", "PgUp"),
        KeyDef("pgdn", "PgDn"),
        KeyDef("slash", "/"),
        KeyDef("dash", "-"),
        KeyDef("pipe", "|"),
        KeyDef("tilde", "~"),
        KeyDef("dot", "."),
        KeyDef("cell", "AI cell"),
        KeyDef("ai", "AI")
    )

    /** Pehli dafa app khulne par yahi buttons dikhte hain. */
    val DEFAULT: List<String> = listOf(
        "esc", "tab", "ctrl", "alt", "up", "down", "ctrlc", "enter", "cell", "ai"
    )

    private val byId: Map<String, KeyDef> = ALL.associateBy { it.id }

    fun isKnown(id: String): Boolean = byId.containsKey(id)

    fun labelOf(id: String): String = byId[id]?.label ?: id
}
