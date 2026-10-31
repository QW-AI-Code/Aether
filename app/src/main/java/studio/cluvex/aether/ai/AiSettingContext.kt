package studio.cluvex.aether.ai

import android.content.Context
import studio.cluvex.aether.scanner.CleanIpScanManager

/** Allowlisted non-profile settings only. Never serialise preferences or the vault wholesale. */
internal object AiSettingContext {
    val scannerTopics = setOf(AiTopic.CLEAN_IP_SCANNER, AiTopic.CLEAN_IP_PROFILE,
        AiTopic.CLEAN_IP_FAST, AiTopic.CLEAN_IP_BALANCED, AiTopic.CLEAN_IP_DEEP, AiTopic.CLEAN_IP_LIMIT)
    fun scanner(options: CleanIpScanManager.Options): String {
        val p = options.profile
        return "profile=${p.name}; candidates=${p.candidateBudget}; timeoutMs=${p.timeoutMs}; " +
            "probesPerIp=${p.probesPerIp}; parallelism=${p.parallelism}; " +
            "manualCeilingEnabled=${options.manual}; savedCeilingMs=${options.ceiling.coerceIn(50, 5000)}; " +
            "preferences are explanation-only, not writable AI patch keys"
    }
    fun read(context: Context, topic: AiTopic): String? = when {
        topic in scannerTopics -> scanner(CleanIpScanManager.options(context))
        topic == AiTopic.CONNECTION_SOUND -> "enabled=${studio.cluvex.aether.core.ConnectionSounds.enabled(context)}"
        else -> null
    }
}
