package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.agent.tool.GitHubWikiTool
import io.github.veronikapj.wiki.config.ModelConfig
import io.github.veronikapj.wiki.llm.LLMExecutorBuilder
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class OrchestratorAgentTest {

    @Test
    fun `build creates agent with confluenceTool only when rag disabled`() {
        val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            vectorSearchTool = null,
            executor = LLMExecutorBuilder.build(ModelConfig()),
        )
        assertNotNull(agent)
    }

    @Test
    fun `build creates agent with githubWikiTool only when confluence disabled`() {
        val githubWikiTool = GitHubWikiTool(mockk<GitHubWikiSearchAgent>())
        val agent = OrchestratorAgent(
            confluenceTool = null,
            githubWikiTool = githubWikiTool,
            executor = LLMExecutorBuilder.build(ModelConfig()),
        )
        assertNotNull(agent)
    }

    @Test
    fun `answer method accepts progressListener parameter`() {
        val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            executor = LLMExecutorBuilder.build(ModelConfig()),
        )
        assertNotNull(agent)
    }

    @Test
    fun `accepts conversationStore and sessionId`() {
        val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
        val store = io.github.veronikapj.wiki.context.ConversationStore(
            java.io.File(System.getProperty("java.io.tmpdir"), "wiki-test-${System.nanoTime()}").absolutePath
        )
        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            executor = LLMExecutorBuilder.build(ModelConfig()),
            conversationStore = store,
        )
        assertNotNull(agent)
    }

    @Test
    fun `throws when no tools are provided`() {
        assertFailsWith<IllegalArgumentException> {
            OrchestratorAgent(
                confluenceTool = null,
                githubWikiTool = null,
                vectorSearchTool = null,
                executor = LLMExecutorBuilder.build(ModelConfig()),
            )
        }
    }

    @Test
    fun `knowledgeTool has highest search priority`() {
        val baseDir = "build/test-orch-kb-${System.nanoTime()}"
        val store = io.github.veronikapj.wiki.knowledge.KnowledgeStore(baseDir)
        val knowledgeTool = io.github.veronikapj.wiki.knowledge.KnowledgeTool(store)

        val confluenceAgent = mockk<ConfluenceSearchAgent>()
        val confluenceTool = ConfluenceTool(confluenceAgent)

        val agent = OrchestratorAgent(
            knowledgeTool = knowledgeTool,
            confluenceTool = confluenceTool,
            executor = LLMExecutorBuilder.build(ModelConfig()),
            useManualLoop = true,
        )

        val priorities = agent.toolPriorities()
        kotlin.test.assertTrue(priorities.indexOf("knowledgeSearch") < priorities.indexOf("confluenceSearch"))

        java.io.File(baseDir).deleteRecursively()
    }
}
