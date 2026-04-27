package io.github.veronikapj.wiki.knowledge

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.agent.tool.SourceTracker

class KnowledgeTool(
    private val store: KnowledgeStore,
    private val tracker: SourceTracker? = null,
) {

    @Tool("knowledgeSearch")
    @LLMDescription("로컬 지식베이스(ingest된 URL·텍스트)에서 키워드로 검색합니다. Confluence보다 먼저 검색하세요.")
    fun knowledgeSearch(
        @LLMDescription("검색할 키워드 또는 질문")
        query: String,
    ): String {
        tracker?.record("KnowledgeBase")
        val pages = store.loadAll()
        if (pages.isEmpty()) return "지식베이스가 비어있습니다. 관련 내용을 찾을 수 없습니다."

        val terms = query.lowercase().split(" ", "　").filter { it.length >= 2 }
        val matched = pages.filter { (path, content) ->
            val target = (path + " " + content).lowercase()
            terms.any { target.contains(it) }
        }

        if (matched.isEmpty()) return "지식베이스에서 관련 내용을 찾을 수 없습니다."

        return matched.take(3).joinToString("\n\n---\n\n") { (path, content) ->
            "*[$path]*\n${content.take(500)}"
        }
    }
}
