package com.tony.appbooster.data.util

/**
 * Parses ART/dexopt related command outputs into normalized compiler filter signals.
 *
 * Business purpose:
 * - Centralizes parsing logic so repository code stays readable.
 * - Avoids reliance on shell utilities like grep/head.
 * - Improves testability by making parsing pure and deterministic.
 */
internal object DexoptStatusParser {

    /**
     * Marker returned when a package is listed in the dexopt dump but its own block
     * reports no compiler filter.
     *
     * This is *inconclusive*, not a filter: the package must still be resolved through
     * the remaining detection steps. Treating it as an answer makes every such package
     * look permanently unoptimised, because compiling it does not change what the dump
     * reports about it.
     */
    const val FILTER_PRESENT_NO_DETAIL = "unknown-present"

    /**
     * Attempts to interpret the output of `cmd package compile --check <package>`.
     *
     * Different Android versions output different formats. We support:
     * - `true` / `false`
     * - Strings containing "compilation needed" / "compilation not needed"
     *
     * @param output Raw command output.
     * @return True if the system says compilation is needed, false if not needed, or null if unknown.
     */
    fun parseCompileCheckNeedsOptimization(output: String): Boolean? {
        if (output.isBlank()) return null

        val lower = output.trim().lowercase()

        if (lower == "true") return true
        if (lower == "false") return false

        if (lower.contains("compilation") && lower.contains("not") && lower.contains("needed")) return false
        if (lower.contains("compilation") && lower.contains("needed")) return true

        if (lower.contains("need") && lower.contains("compile")) {
            if (lower.contains("not") && lower.contains("needed")) return false
            if (lower.contains("needed")) return true
        }

        return null
    }

    /**
     * Checks whether the given package appears in a dexopt dump at all.
     *
     * Some Android builds omit compiler-filter lines for overlay/system packages.
     * In those cases, presence alone is a useful signal that the system is aware
     * of dexopt state, even if details are not reported.
     */
    fun isPackagePresentInDexoptDump(packageName: String, dump: String): Boolean {
        if (dump.isBlank()) return false

        // Match common bracketed forms:
        // - "[com.example.app]"
        // - "Dexopt state:\n  [com.example.app]"
        // - "Dexopt state:  [com.example.app]"
        val needle = "[$packageName]"
        return dump.contains(needle)
    }

    /**
     * Parses compiler filter for a package from the full `dumpsys package dexopt` output.
     *
     * Supports multiple formats across Android versions:
     * - Explicit filter lines (compiler-filter=speed-profile)
     * - Status annotations ([status=speed])
     * - Newer builds that only list the package in a "Dexopt state" section without details
     *   (in this case returns "unknown-present").
     */
    fun parseCompilerFilterFromDexoptDump(packageName: String, dump: String): String? {
        val lines = dump.lineSequence().toList()

        // Prefer the bracketed header; fall back to a bare mention of the package.
        // Both matches are token-bounded so that a query for "com.example.app" can never
        // latch onto the block of an unrelated "com.example.application".
        val bracketed = "[$packageName]"
        val bracketedIdx = lines.indexOfFirst { it.contains(bracketed) }
        val idx = if (bracketedIdx >= 0) {
            bracketedIdx
        } else {
            lines.indexOfFirst { containsPackageToken(it, packageName) }
        }
        if (idx < 0) return null

        // Stop at the next package header so a package listed without filter details
        // does not inherit the filter reported for the package that follows it.
        val hardLimit = minOf(idx + DUMP_SCAN_WINDOW_LINES, lines.size)
        for (i in idx until hardLimit) {
            val line = lines[i]
            if (i > idx && startsOtherPackageBlock(line, packageName)) break
            parseCompilerFilterFromLine(line.trim().lowercase())?.let { return it }
        }

        // If we can see the package in a Dexopt state section but no filter lines are provided,
        // return a marker so callers can treat it differently from "not found".
        return if (isPackagePresentInDexoptDump(packageName, dump)) FILTER_PRESENT_NO_DETAIL else null
    }

    /**
     * Checks whether [line] mentions [packageName] as a whole token rather than as a
     * prefix of a longer package name (`com.example.app` vs `com.example.app2`).
     */
    internal fun containsPackageToken(line: String, packageName: String): Boolean {
        var from = 0
        while (from <= line.length - packageName.length) {
            val at = line.indexOf(packageName, from)
            if (at < 0) return false
            val before = line.getOrNull(at - 1)
            val after = line.getOrNull(at + packageName.length)
            if (!isPackageNameChar(before) && !isPackageNameChar(after)) return true
            from = at + 1
        }
        return false
    }

    /** True when [line] is the bracketed header of a package other than [packageName]. */
    private fun startsOtherPackageBlock(line: String, packageName: String): Boolean {
        val header = PACKAGE_HEADER_REGEX.find(line)?.groupValues?.getOrNull(1) ?: return false
        return header != packageName
    }

    private fun isPackageNameChar(char: Char?): Boolean =
        char != null && (char.isLetterOrDigit() || char == '.' || char == '_')

    /** Maximum number of lines inspected after a package header. */
    private const val DUMP_SCAN_WINDOW_LINES = 30

    /** Matches a bracketed dotted identifier such as `[com.example.app]`. */
    private val PACKAGE_HEADER_REGEX =
        Regex("""\[([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)]""")

    /**
     * Extracts a compiler filter keyword from a single lowercased line.
     */
    fun parseCompilerFilterFromLine(lowercasedLine: String): String? {
        return when {
            lowercasedLine.contains("speed-profile") -> "speed-profile"
            lowercasedLine.contains("everything") -> "everything"
            lowercasedLine.contains("[status=speed]") || (lowercasedLine.contains("speed") && !lowercasedLine.contains("profile")) -> "speed"
            lowercasedLine.contains("quicken") -> "quicken"
            lowercasedLine.contains("verify") -> "verify"
            lowercasedLine.contains("run-from-apk") || lowercasedLine.contains("extract") -> "extract"
            else -> null
        }
    }
}

