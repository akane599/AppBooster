package com.tony.appbooster.data.util

import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.AppCompilationInfo
import com.tony.appbooster.domain.model.common.LogEntryType
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the current compilation status for individual packages by
 * querying multiple system data sources in order of reliability and cost.
 *
 * The multi-step fallback strategy is:
 * 1. **Session cache** — zero shell calls for packages optimised this session.
 * 2. **`dumpsys package dexopt`** — single call cached for the entire run.
 * 3. **`dumpsys package <pkg>`** — per-package fallback.
 * 4. **`cmd package compile --check`** — per-package binary yes/no.
 * 5. **OAT file scan (`ls`)** — absolute last resort.
 *
 * @property shellDataSource Data source that executes shell commands.
 * @property logger Shared logger for diagnostic output.
 * @constructor Creates a resolver with required shell and logging dependencies.
 */
@Singleton
class CompilationInfoResolver @Inject constructor(
    private val shellDataSource: AdbShellDataSource,
    private val logger: OptimizationLogger
) {

    companion object {
        /** Hours before a session-cached optimisation result expires. */
        private const val SESSION_CACHE_VALIDITY_HOURS = 24L

        /** Milliseconds per hour — avoids magic literal in cache TTL checks. */
        private const val MS_PER_HOUR = 1000L * 60 * 60

        /** Common Android date format found in `dumpsys package` output. */
        private val DUMPSYS_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    /**
     * Packages successfully optimised in this session (package → timestamp).
     * Avoids re-checking packages we just compiled.
     */
    private val recentlyOptimizedPackages = mutableMapOf<String, Long>()

    /**
     * Cached output of `dumpsys package dexopt` for the current analysis run.
     * Avoids running an expensive global dump for every package.
     */
    private var cachedDexoptDump: String? = null

    /**
     * Whether the global dexopt dump was already attempted for the current run.
     *
     * Tracked separately from [cachedDexoptDump] because a failed dump also
     * yields `null`; without this flag the expensive dump would be retried once
     * per package on devices where it is unavailable.
     */
    private var dexoptDumpAttempted = false

    /**
     * Per-package `dumpsys package <pkg>` cache for overlay detection.
     */
    private val cachedPackageDumps = mutableMapOf<String, String>()

    /**
     * Records a package as successfully optimised so future queries skip it.
     *
     * @param packageName Package that was just compiled.
     */
    fun markOptimized(packageName: String) {
        recentlyOptimizedPackages[packageName] = System.currentTimeMillis()
    }

    /**
     * Resets all per-run caches. Must be called at the start of each
     * optimisation or analysis run.
     */
    fun resetCaches() {
        cachedDexoptDump = null
        dexoptDumpAttempted = false
        cachedPackageDumps.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public query entry point
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Queries compilation status for [packageName] through up to five
     * fallback steps, returning as soon as a definitive answer is found.
     *
     * @param packageName Package to query.
     * @param targetFilter The optimisation filter we intend to apply.
     * @return [AppCompilationInfo] with parsed compilation details.
     */
    suspend fun queryPackageCompilationInfo(
        packageName: String,
        targetFilter: String
    ): AppCompilationInfo {

        // ── Step 1: session cache ──────────────────────────────────────────────
        fromSessionCache(packageName, targetFilter)?.let { return it }

        var lastUpdateTimeMs: Long? = null

        // ── Step 2: global dexopt dump ─────────────────────────────────────────
        // A real filter is definitive. "Present but no details" is not an answer, so
        // it falls through to the cheaper-to-be-wrong steps below rather than
        // short-circuiting them.
        val dexoptFilter = fromDexoptDump(packageName)
        if (dexoptFilter != null && dexoptFilter != DexoptStatusParser.FILTER_PRESENT_NO_DETAIL) {
            return resolveCompilationInfo(packageName, dexoptFilter, lastUpdateTimeMs, targetFilter)
        }

        // ── Step 3: per-package dumpsys ────────────────────────────────────────
        fromPackageDumpsys(packageName, targetFilter)?.let { (info, updateMs) ->
            if (info != null) return info
            if (updateMs != null) lastUpdateTimeMs = updateMs
        }

        // ── Step 4: compile --check ────────────────────────────────────────────
        fromCompileCheck(packageName, lastUpdateTimeMs)?.let { return it }

        // ── Step 5: OAT file scan ──────────────────────────────────────────────
        val oatFilter = fromOatScan(packageName)

        // Nothing conclusive. Prefer on-disk evidence, then fall back to the
        // "listed without details" marker so it can be classified one last time.
        return resolveCompilationInfo(
            packageName,
            oatFilter ?: dexoptFilter,
            lastUpdateTimeMs,
            targetFilter
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Fallback steps
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Step 1 — returns cached info when the package was optimised this session.
     */
    private fun fromSessionCache(
        packageName: String,
        targetFilter: String
    ): AppCompilationInfo? {
        val cachedTime = recentlyOptimizedPackages[packageName] ?: return null
        val hoursSince = (System.currentTimeMillis() - cachedTime) / MS_PER_HOUR
        if (hoursSince >= SESSION_CACHE_VALIDITY_HOURS) return null

        return AppCompilationInfo(
            packageName = packageName,
            compilerFilter = targetFilter,
            lastCompilationTimeMs = cachedTime,
            lastUpdateTimeMs = null,
            oatFileExists = true,
            skipReason = AppCompilationInfo.SkipReason.RecentlyOptimized(0, targetFilter),
            needsOptimization = false
        )
    }

    /**
     * Step 2 — parses the global `dumpsys package dexopt` output (fetched once
     * per run and cached).
     *
     * @return The compiler filter reported for [packageName],
     *         [DexoptStatusParser.FILTER_PRESENT_NO_DETAIL] when the package is listed
     *         without one, or null when the dump is unavailable or omits the package.
     */
    private suspend fun fromDexoptDump(packageName: String): String? {
        if (!dexoptDumpAttempted) {
            logger.addLogEntry(LogEntryType.ANALYZING, "Dexopt dump",
                detail = "dumpsys package dexopt (once per run)")
            cachedDexoptDump = fetchDexoptDump()
            dexoptDumpAttempted = true
        } else {
            logger.addLogEntry(LogEntryType.INFO, "Dexopt dump",
                packageName = packageName, detail = "cache hit")
        }

        val dump = cachedDexoptDump ?: return null
        val filter = DexoptStatusParser.parseCompilerFilterFromDexoptDump(packageName, dump)

        if (filter != null) {
            logger.addLogEntry(LogEntryType.INFO, "Dexopt status",
                packageName = packageName,
                detail = if (filter == DexoptStatusParser.FILTER_PRESENT_NO_DETAIL) {
                    "listed without a compiler filter"
                } else {
                    "filter=$filter"
                })
        }
        return filter
    }

    /**
     * Step 3 — per-package `dumpsys package <pkg>`.
     *
     * @return Pair of (resolved [AppCompilationInfo] or null, lastUpdateTimeMs or null).
     */
    private suspend fun fromPackageDumpsys(
        packageName: String,
        targetFilter: String
    ): Pair<AppCompilationInfo?, Long?>? {
        logger.addLogEntry(LogEntryType.ANALYZING, "Fallback: package dump",
            packageName = packageName, detail = "dumpsys package")

        val output = shellDataSource.executeCommand("dumpsys package $packageName")
            .getOrNull()

        if (output == null) {
            logger.addLogEntry(LogEntryType.ERROR, "Package dump failed",
                packageName = packageName)
            return null
        }

        var filter: String? = null
        var lastUpdateTimeMs: Long? = null

        output.lineSequence().forEach { line ->
            val lower = line.trim().lowercase()
            when {
                filter == null && (lower.contains("status=") || lower.contains("compiler") ||
                    lower.contains("compilerfilter") || lower.contains("compiler-filter")) ->
                    filter = DexoptStatusParser.parseCompilerFilterFromLine(lower)

                lower.startsWith("lastupdatetime=") && lastUpdateTimeMs == null ->
                    lastUpdateTimeMs = parseTimestamp(line.substringAfter("=").trim())
            }
        }

        return if (filter != null) {
            logger.addLogEntry(LogEntryType.INFO, "Dexopt status",
                packageName = packageName, detail = "filter=$filter")
            resolveCompilationInfo(packageName, filter, lastUpdateTimeMs, targetFilter) to lastUpdateTimeMs
        } else {
            logger.addLogEntry(LogEntryType.INFO, "Package dump",
                packageName = packageName, detail = "no compiler filter reported")
            null to lastUpdateTimeMs
        }
    }

    /**
     * Step 4 — `cmd package compile --check`.
     */
    private suspend fun fromCompileCheck(
        packageName: String,
        lastUpdateTimeMs: Long?
    ): AppCompilationInfo? {
        logger.addLogEntry(LogEntryType.ANALYZING, "Fallback: compile check",
            packageName = packageName, detail = "cmd package compile --check")

        val checkResult = shellDataSource.executeCommandDetailed(
            "cmd package compile --check $packageName"
        )
        val check = checkResult.getOrNull()

        when {
            check == null -> logger.addLogEntry(LogEntryType.ERROR, "Compile check failed",
                packageName = packageName, detail = checkResult.exceptionOrNull()?.message)

            !check.isSuccess -> logger.addLogEntry(LogEntryType.INFO, "Compile check unsupported",
                packageName = packageName,
                detail = check.stderr.trim().ifEmpty { "exitCode=${check.exitCode}" })

            else -> {
                val output = check.stdout.trim()
                DexoptStatusParser.parseCompileCheckNeedsOptimization(output)?.let { needsOpt ->
                    logger.addLogEntry(LogEntryType.INFO, "Compile check result",
                        packageName = packageName,
                        detail = if (needsOpt) "needs optimization" else "already optimal")
                    return AppCompilationInfo(
                        packageName = packageName,
                        compilerFilter = null,
                        lastCompilationTimeMs = null,
                        lastUpdateTimeMs = lastUpdateTimeMs,
                        oatFileExists = false,
                        skipReason = if (needsOpt) null
                                     else AppCompilationInfo.SkipReason.AlreadyOptimal("system-check"),
                        needsOptimization = needsOpt
                    )
                }
                logger.addLogEntry(LogEntryType.INFO, "Compile check unparseable",
                    packageName = packageName, detail = output.take(120))
            }
        }
        return null
    }

    /**
     * Step 5 — checks for compiled OAT artifacts on disk.
     *
     * @return Compiler filter marker string, or null if no artefacts found.
     */
    private suspend fun fromOatScan(packageName: String): String? {
        logger.addLogEntry(LogEntryType.ANALYZING, "Fallback: oat scan",
            packageName = packageName, detail = "ls /data/app/.../oat")

        val oatResult = shellDataSource.executeCommand(
            "ls /data/app/*$packageName*/oat/arm64/*.odex || " +
                "ls /data/app/*$packageName*/oat/arm/*.odex"
        )

        val output = oatResult.getOrNull()
        if (output == null) {
            logger.addLogEntry(LogEntryType.ERROR, "OAT scan failed",
                packageName = packageName, detail = oatResult.exceptionOrNull()?.message)
            return null
        }

        val trimmed = output.trim()
        return if (trimmed.isNotEmpty() &&
            !output.contains("No such file", ignoreCase = true) &&
            !output.contains("Permission denied", ignoreCase = true)
        ) {
            logger.addLogEntry(LogEntryType.INFO, "OAT files found", packageName = packageName)
            "unknown-optimized"
        } else {
            logger.addLogEntry(LogEntryType.INFO, "OAT files not accessible",
                packageName = packageName)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Decision logic
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Maps a resolved [compilerFilter] (or null) to the final
     * [AppCompilationInfo.needsOptimization] flag and [AppCompilationInfo.SkipReason].
     *
     * @param packageName Package being evaluated.
     * @param compilerFilter Resolved compiler filter, or null.
     * @param lastUpdateTimeMs App's last-update timestamp, if available.
     * @param targetFilter The optimisation filter we intend to apply.
     * @return Fully resolved [AppCompilationInfo].
     */
    internal suspend fun resolveCompilationInfo(
        packageName: String,
        compilerFilter: String?,
        lastUpdateTimeMs: Long?,
        targetFilter: String
    ): AppCompilationInfo {
        val (needsOptimization, skipReason) = when {
            compilerFilter == null -> true to null

            // "verify" ⇒ no runtime profile; profile-guided compilation is pointless
            compilerFilter == "verify" && targetFilter.lowercase() == "speed-profile" ->
                false to AppCompilationInfo.SkipReason.NoProfile(compilerFilter)

            // Listed in the dexopt dump but no filter reported, and nothing else could
            // resolve it. Compiling such a package does not change what the dump says
            // about it, so queueing it would recompile it on every run forever.
            compilerFilter == DexoptStatusParser.FILTER_PRESENT_NO_DETAIL ->
                resolveUndetailedPackage(packageName)

            // OAT exists but exact filter unknown — conservative re-compile for speed-profile
            compilerFilter == "unknown-optimized" -> {
                val needs = targetFilter.lowercase() == "speed-profile"
                needs to if (needs) null
                         else AppCompilationInfo.SkipReason.RecentlyOptimized(0, "compiled")
            }

            isFilterOptimalForTarget(compilerFilter, targetFilter) ->
                false to AppCompilationInfo.SkipReason.RecentlyOptimized(0, compilerFilter)

            else -> true to null
        }

        return AppCompilationInfo(
            packageName = packageName,
            compilerFilter = compilerFilter,
            lastCompilationTimeMs = null,
            lastUpdateTimeMs = lastUpdateTimeMs,
            oatFileExists = compilerFilter != null,
            skipReason = skipReason,
            needsOptimization = needsOptimization
        )
    }

    /**
     * Classifies a package that the dexopt dump listed without any compiler filter,
     * and that no other detection step could resolve.
     *
     * Both outcomes skip the package. The system knows about its dexopt state and
     * reports nothing actionable — typically an overlay/RRO, a resource-only APK, or a
     * package whose code the platform manages — so re-compiling it cannot change that
     * verdict. "Force optimization" still recompiles it on demand.
     *
     * @return Pair of (needsOptimization, skipReason).
     */
    private suspend fun resolveUndetailedPackage(
        packageName: String
    ): Pair<Boolean, AppCompilationInfo.SkipReason?> {
        val dump = cachedPackageDumps[packageName] ?: run {
            shellDataSource.executeCommand("dumpsys package $packageName")
                .getOrNull()?.also { cachedPackageDumps[packageName] = it }
        }
        val isOverlay = PackageClassifier.isOverlayLike(packageName, dump)

        return if (isOverlay) {
            logger.addLogEntry(LogEntryType.INFO, "Overlay classification",
                packageName = packageName, detail = "Confirmed as overlay/RRO")
            false to AppCompilationInfo.SkipReason.AlreadyOptimal("overlay/rro")
        } else {
            logger.addLogEntry(LogEntryType.INFO, "Dexopt classification",
                packageName = packageName, detail = "No compiler filter reported → skipping")
            false to AppCompilationInfo.SkipReason.AlreadyOptimal("no dexopt details")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Fetches and caches the global `dumpsys package dexopt` output.
     *
     * @return Dexopt dump string on success, or null on failure.
     */
    private suspend fun fetchDexoptDump(): String? {
        val result = shellDataSource.executeCommandDetailed("dumpsys package dexopt")
        val dexopt = result.getOrNull()

        when {
            dexopt == null ->
                logger.addLogEntry(LogEntryType.ERROR, "Dexopt dump failed",
                    detail = result.exceptionOrNull()?.message)
            !dexopt.isSuccess ->
                logger.addLogEntry(LogEntryType.ERROR, "Dexopt dump failed",
                    detail = dexopt.stderr.trim().ifEmpty { "exitCode=${dexopt.exitCode}" })
        }

        return if (dexopt?.isSuccess == true) dexopt.stdout else null
    }

    /**
     * Determines if the current compiler filter is already optimal for the
     * requested target filter.
     *
     * @param currentFilter The compiler filter currently applied to the app.
     * @param targetFilter The target filter the user wants to apply.
     * @return True if re-compilation would be redundant.
     */
    internal fun isFilterOptimalForTarget(currentFilter: String, targetFilter: String): Boolean {
        val current = currentFilter.lowercase()
        val target = targetFilter.lowercase()

        return when {
            current == "everything" -> true
            current == "speed" && target in setOf("speed", "speed-profile") -> true
            current == "speed-profile" && target == "speed-profile" -> true
            else -> false
        }
    }

    /**
     * Parses a timestamp string from `dumpsys package` output.
     *
     * Supports epoch-millis numbers and the common `yyyy-MM-dd HH:mm:ss` format.
     *
     * @param timeStr Raw timestamp string.
     * @return Epoch milliseconds, or null if parsing fails.
     */
    internal fun parseTimestamp(timeStr: String): Long? {
        timeStr.toLongOrNull()?.let { return it }

        return try {
            val ldt = LocalDateTime.parse(timeStr, DUMPSYS_DATE_FORMAT)
            ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

