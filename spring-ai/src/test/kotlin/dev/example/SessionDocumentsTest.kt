package dev.example

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class SessionDocumentsTest {

    private val sessions = SessionDocuments.load()
    private val longest = sessions.maxBy { it.description.length }

    @Test
    fun `whole strategy embeds exactly one document per session`() {
        SessionDocuments.documentsFor(ChunkingStrategy.WHOLE, sessions) shouldHaveSize sessions.size
    }

    @Test
    fun `every chunk carries its session title in text and metadata`() {
        ChunkingStrategy.entries.forEach { strategy ->
            val documents = SessionDocuments.documentsFor(strategy, sessions)
            assertSoftly(documents) {
                forEach { doc ->
                    withClue("$strategy: ${doc.text?.take(60)}") {
                        doc.text!! shouldStartWith "title:${doc.metadata["title"]}"
                    }
                }
            }
        }
    }

    @Test
    fun `token chunking actually splits a long description`() {
        SessionDocuments.tokenChunks(longest.description).size shouldBeGreaterThan 1
    }

    @Test
    fun `paragraph chunking keeps every line and merges short ones`() {
        val chunks = SessionDocuments.paragraphChunks(longest.description)
        val lines = longest.description.lines().map { it.trim() }.filter { it.isNotEmpty() }

        chunks.flatMap { it.lines() } shouldBe lines
        chunks.dropLast(1).forEach { it.length shouldBeGreaterThanOrEqual SessionDocuments.PARAGRAPH_MIN_CHARS }
    }

    @Test
    fun `multi vector adds the whole description and the title on top of the token chunks`() {
        val texts = SessionDocuments.textsFor(ChunkingStrategy.MULTI_VECTOR, longest)

        texts.map { it.first }.take(2) shouldBe listOf("whole", "title")
        texts shouldHaveSize 2 + SessionDocuments.tokenChunks(longest.description).size
    }
}
