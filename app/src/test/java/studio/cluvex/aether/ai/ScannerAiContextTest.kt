package studio.cluvex.aether.ai

import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.scanner.CleanIpScanManager
import studio.cluvex.aether.scanner.ScanProfile

class ScannerAiContextTest {
    @Test fun contextUsesActualProfileConstantsAndSavedLimit() {
        for (profile in ScanProfile.entries) {
            val value = AiSettingContext.scanner(CleanIpScanManager.Options(profile, true, 850))
            assertTrue(value.contains("profile=${profile.name}"))
            assertTrue(value.contains("candidates=${profile.candidateBudget}"))
            assertTrue(value.contains("timeoutMs=${profile.timeoutMs}"))
            assertTrue(value.contains("probesPerIp=${profile.probesPerIp}"))
            assertTrue(value.contains("parallelism=${profile.parallelism}"))
            assertTrue(value.contains("manualCeilingEnabled=true"))
            assertTrue(value.contains("savedCeilingMs=850"))
        }
    }
    @Test fun explanationPromptContainsTopicAndActualCurrentContext() {
        for (topic in AiSettingContext.scannerTopics) {
            val value = AiSettingContext.scanner(CleanIpScanManager.Options())
            val prompt = AiPrompts.explain(true, topic, value)
            assertTrue(prompt.contains(topic.detail))
            assertTrue(prompt.contains(value))
            assertTrue(prompt.contains("not writable AI patch keys"))
            assertNotEquals(topic.detail, topic.offlineDetail(true))
            assertNull(topic.profileKey)
        }
    }
    @Test fun cleanIpPreferencesAreNotWritableModelPatches() {
        for (topic in AiSettingContext.scannerTopics) {
            assertFalse(AiPatch.WRITABLE.containsKey(topic.id))
        }
        assertFalse(AiPatch.WRITABLE.containsKey("ceiling"))
        assertFalse(AiPatch.WRITABLE.containsKey("scanProfile"))
    }
    @Test fun allNewTopicIdsAreUniqueAndResolvable() {
        assertEquals(AiTopic.entries.size, AiTopic.entries.map { it.id }.toSet().size)
        for (topic in AiTopic.entries) assertEquals(topic, AiTopic.byId(topic.id))
    }
}
