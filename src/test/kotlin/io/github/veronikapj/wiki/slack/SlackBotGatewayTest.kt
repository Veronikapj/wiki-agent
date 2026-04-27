package io.github.veronikapj.wiki.slack

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlackBotGatewayTest {

    @Test
    fun `extractQuery removes mention tag and trims`() {
        val result = SlackBotGateway.extractQuery("<@U1234ABC> 배포 프로세스 알려줘")
        assertEquals("배포 프로세스 알려줘", result)
    }

    @Test
    fun `extractQuery handles text without mention`() {
        val result = SlackBotGateway.extractQuery("그냥 질문입니다")
        assertEquals("그냥 질문입니다", result)
    }

    @Test
    fun `extractQuery handles multiple mentions`() {
        val result = SlackBotGateway.extractQuery("<@U111> <@U222> 안녕하세요")
        assertEquals("안녕하세요", result)
    }

    @Test
    fun `extractQuery handles mention only`() {
        val result = SlackBotGateway.extractQuery("<@U1234ABC>")
        assertEquals("", result)
    }

    @Test
    fun `FEEDBACK_GUIDE contains thumbsup and thumbsdown shortcodes`() {
        assertTrue(SlackBotGateway.FEEDBACK_GUIDE.contains(":thumbsup:"))
        assertTrue(SlackBotGateway.FEEDBACK_GUIDE.contains(":thumbsdown:"))
    }

    @Test
    fun `FEEDBACK_REACTIONS includes all expected reaction names`() {
        val reactions = SlackBotGateway.FEEDBACK_REACTIONS
        assertEquals(4, reactions.size)
        assertTrue("+1" in reactions)
        assertTrue("-1" in reactions)
        assertTrue("thumbsup" in reactions)
        assertTrue("thumbsdown" in reactions)
    }

    @Test
    fun `classifyDmInput detects URL`() {
        assertEquals(DmInputType.URL, classifyDmInput("https://example.com/page"))
        assertEquals(DmInputType.URL, classifyDmInput("http://blog.example.com"))
    }

    @Test
    fun `classifyDmInput detects long text`() {
        val longText = "가".repeat(500)
        assertEquals(DmInputType.LONG_TEXT, classifyDmInput(longText))
    }

    @Test
    fun `classifyDmInput returns NORMAL for short text`() {
        assertEquals(DmInputType.NORMAL, classifyDmInput("배포 절차가 뭐야?"))
    }
}
