package com.clarklevis.dsh.shared.facade

/** 两端表格导出共用单元格矩阵；调用方传入 AST 的纯文本，不能传 Markdown 源码。 */
object MarkdownTableExport {
    fun plainText(rows: List<List<String>>): String =
        rows.joinToString("\n") { it.joinToString("\t") }

    /** UTF-8 BOM 便于电子表格软件识别中文；CSV 单元格遵循引号转义及 CRLF 行分隔。 */
    fun csv(rows: List<List<String>>): String = "\uFEFF" + rows.joinToString("\r\n", postfix = "\r\n") { row ->
        row.joinToString(",") { cell ->
            if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"${cell.replace("\"", "\"\"")}\""
            } else cell
        }
    }
}
