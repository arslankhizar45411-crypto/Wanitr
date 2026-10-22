package com.collabcode.app.data

enum class DiffKind { SAME, ADD, DEL }

data class DiffLine(val kind: DiffKind, val text: String)

/** Line diff using LCS. Fine for the small files in a phone project. */
fun lineDiff(oldText: String, newText: String): List<DiffLine> {
    val a = oldText.lines()
    val b = newText.lines()
    val n = a.size
    val m = b.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    val out = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { out += DiffLine(DiffKind.SAME, a[i]); i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> { out += DiffLine(DiffKind.DEL, a[i]); i++ }
            else -> { out += DiffLine(DiffKind.ADD, b[j]); j++ }
        }
    }
    while (i < n) { out += DiffLine(DiffKind.DEL, a[i]); i++ }
    while (j < m) { out += DiffLine(DiffKind.ADD, b[j]); j++ }
    return out
}
