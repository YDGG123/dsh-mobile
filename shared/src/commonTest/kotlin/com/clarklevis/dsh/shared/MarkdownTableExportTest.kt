package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.MarkdownTableExport
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTableExportTest {
    @Test fun csvEscapesCommasQuotesAndLineBreaksAndKeepsUnicode() {
        val rows = listOf(listOf("名称", "说明", "空"), listOf("😀", "逗号,\"引号\"\n换行", ""))
        assertEquals("\uFEFF名称,说明,空\r\n😀,\"逗号,\"\"引号\"\"\n换行\",\r\n", MarkdownTableExport.csv(rows))
        assertEquals("名称\t说明\t空\n😀\t逗号,\"引号\"\n换行\t", MarkdownTableExport.plainText(rows))
    }

    @Test fun plainTextKeepsColumnAndRowBoundariesWithoutAddingMarkdown() {
        assertEquals("编号\t任务\n001\t发布版本", MarkdownTableExport.plainText(listOf(listOf("编号", "任务"), listOf("001", "发布版本"))))
    }
}
