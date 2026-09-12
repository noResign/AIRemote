package com.airemote.airemote.model.diff

enum class SplitDiffKind { HUNK, META, LINES }

data class SplitDiffRow(
    val kind: SplitDiffKind = SplitDiffKind.LINES,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
    val oldText: String? = null,
    val newText: String? = null,
    val fullText: String? = null,
)

private data class NumberedLine(val number: Int, val text: String)

private val HUNK_RE = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$")

/** Parse a single-file unified diff into side-by-side rows. */
fun parseSplitDiff(patch: String): List<SplitDiffRow> {
    val rows = mutableListOf<SplitDiffRow>()
    var oldLine = 0
    var newLine = 0
    var inHunk = false
    var pendingOld = mutableListOf<NumberedLine>()
    var pendingNew = mutableListOf<NumberedLine>()

    fun flushPending() {
        val count = maxOf(pendingOld.size, pendingNew.size)
        for (i in 0 until count) {
            val old = pendingOld.getOrNull(i)
            val new = pendingNew.getOrNull(i)
            rows += SplitDiffRow(
                kind = SplitDiffKind.LINES,
                oldLineNumber = old?.number,
                newLineNumber = new?.number,
                oldText = old?.text,
                newText = new?.text,
            )
        }
        pendingOld = mutableListOf()
        pendingNew = mutableListOf()
    }

    val lines = patch.split('\n').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
    for (rawLine in lines) {
        val hunk = HUNK_RE.matchEntire(rawLine)
        if (hunk != null) {
            flushPending()
            oldLine = hunk.groupValues[1].toInt()
            newLine = hunk.groupValues[3].toInt()
            inHunk = true
            rows += SplitDiffRow(kind = SplitDiffKind.HUNK, fullText = rawLine)
            continue
        }

        if (rawLine.startsWith("\\ No newline")) {
            flushPending()
            rows += SplitDiffRow(kind = SplitDiffKind.META, fullText = rawLine)
            continue
        }

        if (!inHunk) {
            rows += SplitDiffRow(kind = SplitDiffKind.META, fullText = rawLine)
            continue
        }

        when {
            rawLine.startsWith("+") -> {
                pendingNew += NumberedLine(newLine, rawLine.substring(1))
                newLine += 1
            }
            rawLine.startsWith("-") -> {
                pendingOld += NumberedLine(oldLine, rawLine.substring(1))
                oldLine += 1
            }
            rawLine.startsWith(" ") -> {
                flushPending()
                val text = rawLine.substring(1)
                rows += SplitDiffRow(
                    kind = SplitDiffKind.LINES,
                    oldLineNumber = oldLine,
                    newLineNumber = newLine,
                    oldText = text,
                    newText = text,
                )
                oldLine += 1
                newLine += 1
            }
            rawLine.isEmpty() -> {
                flushPending()
                rows += SplitDiffRow(
                    kind = SplitDiffKind.LINES,
                    oldLineNumber = oldLine,
                    newLineNumber = newLine,
                    oldText = "",
                    newText = "",
                )
                oldLine += 1
                newLine += 1
            }
            else -> {
                flushPending()
                rows += SplitDiffRow(kind = SplitDiffKind.META, fullText = rawLine)
            }
        }
    }
    flushPending()
    return rows
}
