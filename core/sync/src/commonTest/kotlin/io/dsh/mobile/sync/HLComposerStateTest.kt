package io.dsh.mobile.sync

import io.dsh.mobile.protocol.WireJson
import kotlin.test.*
import kotlinx.serialization.json.*

class HLComposerStateTest {
    private fun json(raw: String) = WireJson.parseToJsonElement(raw).jsonObject

    @Test fun wholeLogUsageUsesDisjointBillingBucketsAndExactCounts() {
        val usage = composerTelemetry(json("""{"tokenUsage":{"uncachedInputTokens":51993,"cacheReadTokens":1118208,"cacheWriteTokens":0,"outputTokens":25224,"reasoningTokens":1000}}""")).usage!!
        assertEquals(1_195_425L, usage.total)
        assertEquals("1,195,425", exactTokens(usage.total)); assertEquals("1.2M", compactTokens(usage.total))
        assertEquals("96", cacheHitPercent(usage.cacheRead, usage.billedInput))
        val written = composerTelemetry(json("""{"tokenUsage":{"uncachedInputTokens":10,"cacheReadTokens":70,"cacheWriteTokens":20,"outputTokens":5}}""")).usage!!
        assertEquals(105L, written.total); assertEquals("70", cacheHitPercent(written.cacheRead, written.billedInput))
    }

    @Test fun missingAndIncompleteUsageIsUnknownAndEmptyUsageIsNotActivity() {
        assertNull(composerTelemetry(null).usage)
        assertNull(composerTelemetry(json("""{"tokenUsage":{"uncachedInputTokens":10,"outputTokens":2}}""")).usage)
        assertNull(composerTelemetry(json("""{"tokenUsage":{"uncachedInputTokens":0,"cacheReadTokens":0,"cacheWriteTokens":0,"outputTokens":0}}""")).usage)
        assertNull(composerTelemetry(json("""{"tokenUsage":{"uncachedInputTokens":"10","cacheReadTokens":0,"cacheWriteTokens":0,"outputTokens":1}}""")).usage)
        assertNull(composerTelemetry(json("""{"tokenUsage":{"uncachedInputTokens":-1,"cacheReadTokens":0,"cacheWriteTokens":0,"outputTokens":1}}""")).usage)
    }

    @Test fun contextPrefersProjectedPressureAndDoesNotForceHeuristicsToMatchProviderTotals() {
        val context = composerTelemetry(json("""{"contextPressure":{"pressureTokens":60000,"projectedTokens":65200,"contextWindow":1048576},"contextBreakdown":{"systemTokens":1400,"toolsTokens":7800,"messageTokens":37500}}""")).context!!
        assertEquals(6, context.percent); assertEquals(65200.0, context.used)
        assertEquals("65.2K", compactTokens(context.used)); assertEquals("1M", compactTokens(context.capacity))
        val parts = context.breakdown!!; assertEquals(46700.0, parts.system + parts.tools + parts.messages)
        assertEquals(25, composerTelemetry(json("""{"contextPressure":{"pressureTokens":250,"contextWindow":1000}}""")).context?.percent)
        assertEquals(100, composerTelemetry(json("""{"contextPressure":{"projectedTokens":1200,"contextWindow":1000}}""")).context?.percent)
    }

    @Test fun unknownCapacityStaysUnknownWhileARealZeroSampleShowsZeroPercent() {
        for (raw in listOf("{}", """{"pressureTokens":20}""", """{"pressureTokens":20,"contextWindow":0}""", """{"contextWindow":1000}""")) {
            assertNull(composerTelemetry(json("""{"contextPressure":$raw}""")).context)
        }
        assertEquals(0, composerTelemetry(json("""{"contextPressure":{"pressureTokens":0,"contextWindow":1000}}""")).context?.percent)
    }

    @Test fun statisticsUseWholeLogTimingAndOnlyTimedDecodeTokens() {
        val stats = composerTelemetry(json("""{"sessionStats":{"turns":3,"steps":31,"llmMs":156000,"toolMs":2300,"ttftMs":3000,"ttftSteps":3,"decodeMs":10000,"decodeTokens":2030}}""")).statistics!!
        assertEquals(3L, stats.turns); assertEquals(31L, stats.steps)
        assertEquals("2分36秒", statisticDuration(stats.modelMs)); assertEquals("2.3秒", statisticDuration(stats.toolMs))
        assertEquals("1秒", statisticDuration(stats.ttftMs / stats.ttftSteps)); assertEquals("203 tok/s", tokensPerSecond(stats.decodeTokens, stats.decodeMs))
        assertNull(tokensPerSecond(100, 0.0)); assertNull(composerTelemetry(json("""{"sessionStats":{"turns":3,"steps":31}}""")).statistics)
    }

    @Test fun cacheFormattingMatchesPinnedWebGoldenReadingsIncludingNearFullHits() {
        // Golden outputs captured from DSH ce7073d ui-chat/chat/token-format.ts.
        assertNull(cacheHitPercent(0, 0)); assertEquals("0", cacheHitPercent(0, 1)); assertEquals("100", cacheHitPercent(1, 1))
        assertEquals("50", cacheHitPercent(1, 2)); assertEquals("56.3", cacheHitPercent(81, 144, 1))
        assertEquals("99.9", cacheHitPercent(999, 1000)); assertEquals("99.95", cacheHitPercent(9995, 10000))
        assertEquals("99.95", cacheHitPercent(99950, 100000)); assertEquals("99.995", cacheHitPercent(99995, 100000))
        assertEquals("99.99999999999999", cacheHitPercent(9_007_199_254_740_990, 9_007_199_254_740_991))
    }

    @Test fun compactNumbersAndDurationsUseWebHalfUpRounding() {
        assertEquals("517", compactTokens(517)); assertEquals("12.2K", compactTokens(12_249))
        assertEquals("517K", compactTokens(517_499)); assertEquals("1.2M", compactTokens(1_200_000))
        assertEquals("1.1秒", statisticDuration(1050.0)); assertEquals("60秒", statisticDuration(59_960.0))
        assertEquals("1分0秒", statisticDuration(60_000.0)); assertEquals("10 tok/s", tokensPerSecond(249, 25_000.0))
    }

    @Test fun permissionLabelsPreserveCustomHostNamesAndMissingProjectionIsNotADefault() {
        assertEquals("工作区内修改", permissionLabel("workspace-write", "Workspace Write"))
        assertEquals("仅可查看", permissionLabel("read-only")); assertEquals("完全权限", permissionLabel("danger-full-access"))
        assertEquals("团队规则", permissionLabel("workspace-write", "团队规则"))
        assertEquals("My Policy", permissionLabel("my-policy")); assertEquals("自定义", permissionLabel("custom"))
        assertNull(composerTelemetry(json("{}") ).context)
    }

    @Test fun statisticsAndPermissionUpdatesAtTheSameSequenceKeepIndependentWatermarks() {
        val reducer = HLProjectionReducer()
        reducer.control(json("""{"type":"projection","sessionId":"s","key":"permissions","seq":20,"value":{"currentValue":"read-only"}}"""))
        reducer.control(json("""{"type":"projection","sessionId":"s","key":"tokenUsage","seq":20,"value":{"uncachedInputTokens":10,"cacheReadTokens":20,"cacheWriteTokens":0,"outputTokens":5}}"""))
        reducer.baseline("s", json("""{"asOfSeq":18,"values":{"permissions":{"currentValue":"workspace-write"},"tokenUsage":{"uncachedInputTokens":0,"cacheReadTokens":0,"cacheWriteTokens":0,"outputTokens":0},"contextPressure":{"pressureTokens":50,"contextWindow":100}}}"""))
        assertEquals("read-only", reducer.values("s")?.get("permissions")?.jsonObject?.get("currentValue")?.jsonPrimitive?.content)
        assertEquals(35L, composerTelemetry(reducer.values("s")).usage?.total)
        assertEquals(50, composerTelemetry(reducer.values("s")).context?.percent)
        assertNull(composerTelemetry(reducer.values("other-session")).usage)
        reducer.reset(); assertNull(reducer.values("s"))
    }
}
