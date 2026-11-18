package io.github.astromg01.rbxmicrofix.core

enum class PerformanceProfile(
    val title: String,
    val description: String,
    val downscaleFactor: String,
) {
    QUALITY("Nitidez", "Redução leve da carga de renderização.", "0.9"),
    HYBRID("Equilibrado", "Equilíbrio entre definição e carga gráfica.", "0.85"),
    PERFORMANCE("Desempenho", "Reduz a carga de renderização mantendo boa definição.", "0.8"),
    MAX_PERFORMANCE("Máximo", "Redução mais intensa do backbuffer para aliviar a GPU.", "0.75"),
}

object GameOverlayConfig {
    val allowedDownscaleFactors = setOf("0.9", "0.85", "0.8", "0.75")
    val allowedFpsOverrides = setOf("disable", "60")
    const val LOADING_BOOST_DURATION_MS = 5_000

    fun withPerformanceProfile(
        original: String?,
        downscaleFactor: String,
        fpsOverride: String,
        useAngle: Boolean,
    ): String {
        require(downscaleFactor in allowedDownscaleFactors) { "Unsupported downscale factor" }
        require(fpsOverride in allowedFpsOverrides) { "Unsupported FPS override" }

        val segments = original
            .orEmpty()
            .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            ?.split(':')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

        val retained = segments.filterNot { segment -> segmentMode(segment) == PERFORMANCE_MODE }
        val existingPerformanceSegment = segments.firstOrNull { segment ->
            segmentMode(segment) == PERFORMANCE_MODE
        }

        val values = linkedMapOf<String, String>()
        existingPerformanceSegment
            ?.split(',')
            ?.map(String::trim)
            ?.forEach { token ->
                val separator = token.indexOf('=')
                if (separator > 0) {
                    values[token.substring(0, separator).trim()] = token.substring(separator + 1).trim()
                }
            }

        values["mode"] = PERFORMANCE_MODE
        values["downscaleFactor"] = downscaleFactor
        values["fps"] = fpsOverride
        values["useAngle"] = useAngle.toString()
        values["loadingBoost"] = LOADING_BOOST_DURATION_MS.toString()

        val performanceSegment = values.entries.joinToString(",") { (key, value) -> "$key=$value" }
        return (retained + performanceSegment).joinToString(":")
    }

    private fun segmentMode(segment: String): String? =
        segment.split(',').firstNotNullOfOrNull { token ->
            val pair = token.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].trim() == "mode") pair[1].trim() else null
        }

    private const val PERFORMANCE_MODE = "2"
}

object GameModeParser {
    private val currentModePattern = Regex(
        "current mode\\s*:\\s*(unsupported|standard|performance|battery|custom|[0-4])",
        RegexOption.IGNORE_CASE,
    )
    private val availableModesPattern = Regex(
        "available game modes\\s*:\\s*\\[([^\\]]*)\\]",
        RegexOption.IGNORE_CASE,
    )

    fun parseMode(raw: String): Int? = when (raw.trim().lowercase()) {
        "unsupported" -> 0
        "standard" -> 1
        "performance" -> 2
        "battery" -> 3
        "custom" -> 4
        else -> raw.trim().toIntOrNull()?.takeIf { it in 0..4 }
    }

    fun parseCurrentMode(commandOutput: String): Int? =
        currentModePattern.find(commandOutput)?.groupValues?.getOrNull(1)?.let(::parseMode)

    fun parseAvailableModes(commandOutput: String): Set<Int> =
        availableModesPattern.find(commandOutput)
            ?.groupValues
            ?.getOrNull(1)
            ?.split(',')
            ?.mapNotNull(::parseMode)
            ?.toSet()
            .orEmpty()

    /**
     * The shell "set" fallback writes a persistent user override. It is safe to
     * use only when GameManager confirms there is no existing intervention config.
     */
    fun reportsNoInterventionConfig(commandOutput: String): Boolean =
        commandOutput.contains("no intervention found", ignoreCase = true)

    fun modeName(mode: Int): String = when (mode) {
        1 -> "standard"
        2 -> "performance"
        3 -> "battery"
        4 -> "custom"
        else -> "unsupported"
    }
}
