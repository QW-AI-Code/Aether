package studio.cluvex.aether.custom

/** Failed/untested reports are never treated as a successful zero-ms probe. */
enum class PingQuality { EXCELLENT, FAIR, POOR, UNAVAILABLE;
    companion object {
        fun of(report: ConfigPing.Report?): PingQuality = when {
            report?.ok != true || report.ms < 0 -> UNAVAILABLE
            report.ms < 150 -> EXCELLENT
            report.ms <= 300 -> FAIR
            else -> POOR
        }
    }
}
