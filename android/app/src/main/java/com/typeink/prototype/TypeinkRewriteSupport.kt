package com.typeink.prototype

private const val EDGE_PUNCTUATION = " \t\r\n，。！？；：,.!?;:()（）[]【】<>《》\\\"'“”‘’"
private val quotedTextRegex = Regex("[\\\"'“”‘’]([^\\\"'“”‘’]+)[\\\"'“”‘’]")
private val firstCharRegex = Regex("(?:把)?(?:第(?:一|1)个字|首字|第一个字)(?:改成|换成|替换成|改为)(.+)$")
private val lastCharRegex = Regex("(?:把)?(?:最后(?:一|1)个字|尾字|最后一个字)(?:改成|换成|替换成|改为)(.+)$")
private val scopedReplaceRegex =
    Regex(
        "(?:把|将)[\\\"'“‘]?([^，。！？；\\\"'“”‘’]+?)[\\\"'”’]?的[\\\"'“‘]?([^，。！？；\\\"'“”‘’]{1,6})[\\\"'”’]?(?:改成|换成|替换成|改为)[\\\"'“‘]?([^，。！？；]+)",
    )
private val generalReplaceRegex =
    Regex(
        "(?:把|将)[\\\"'“‘]?([^，。！？；\\\"'“”‘’]+?)[\\\"'”’?]?(?:改成|换成|替换成|改为)[\\\"'“‘]?([^，。！？；]+)",
    )

object TypeinkRewriteSupport {
    data class RewriteMessage(
        val role: String,
        val content: String,
    )

    val systemPrompt: String =
        """
        你是手机 AI 语音输入法里的即时重写器。
        你的输出会直接写进输入框，所以必须只输出最终文本。
        不要解释，不要分析，不要加引号，不要写“润色后：”。
        默认不要输出 Markdown；但如果任务明确要求列表排版，可以输出以 `- ` 开头的纯文本列表。
        你的任务不是机械转写，而是把用户真正想表达的意思整理成可直接发布的书面文字。
        你必须严格保留用户的核心观点和事实，不得编造新信息。
        默认采用“最小改动”策略：能不改就不改，能少改就少改。
        默认优先保留用户原本的表达风格、说话语气、句式节奏、提问方式和个人措辞。
        默认要求去掉明显累赘、口头禅、重复和无意义噪声，并顺一顺病句，但不要把语气改得不像用户本人。
        除非用户明确要求“更正式”“更专业”“润色成书面语”“整理成发布稿”，否则不要主动把语气升级得更书面。
        如果当前会话已经明确指定了风格模式，优先服从当前会话风格模式。
        不要把用户原本自然、直接、有个人风格的话，擅自改成生硬、抽象、过度书面化的表达。
        例如：不要把“你测试一下就知道了”改成“实际测试后便知其原理”。
        你必须自动去除语气词、口头禅、重复结巴和无意义赘述。
        但只删除真正无意义的噪声，不要把有表达风格的口语句式、反问句、强调语气一并抹平。
        如果语音里包含修改指令、撤回指令、自我纠正或“边说边改”的元指令，你必须执行这些指令，而不是把指令本身写进结果。
        如果内容明显是多个并列观点、步骤或事项，优先整理成简洁的项目列表。
        正确处理中英混杂、专有名词和术语拼写，并在中英文之间自动补空格。
        如果执行完用户的修改指令后最终结果应为空，请只输出一个半角空格，不要输出任何其他字符。
        """.trimIndent().trim()

    fun buildMessages(
        sourceText: String,
        snapshot: SessionInputSnapshot,
        styleMode: TypeinkStyleMode,
    ): List<RewriteMessage> {
        return listOf(
            RewriteMessage(role = "system", content = systemPrompt),
            RewriteMessage(role = "user", content = buildUserPrompt(sourceText, snapshot, styleMode)),
        )
    }

    fun buildFallbackText(
        sourceText: String,
        snapshot: SessionInputSnapshot,
    ): String {
        if (snapshot.rewriteMode) {
            return "${snapshot.preText}${snapshot.selectedText}${snapshot.postText}".trim()
        }
        return sourceText.trim()
    }

    fun tryApplyDirectEdit(
        instruction: String,
        snapshot: SessionInputSnapshot,
    ): String? {
        if (!snapshot.rewriteMode) {
            return null
        }

        val spoken = instruction.trim()
        if (spoken.isBlank()) {
            return null
        }

        if (
            listOf("上一句删掉", "删掉上一句", "上一句不要了", "这句删掉", "这一句删掉", "这句不要了").any {
                normalizeCommandText(spoken).contains(normalizeCommandText(it))
            }
        ) {
            return "${snapshot.preText}${snapshot.postText}".ifBlank { " " }
        }

        val selected = snapshot.selectedText.trim()
        if (selected.isBlank()) {
            return null
        }

        val rewritten =
            applyPositionEdit(spoken, selected)
                ?: applyScopedReplace(spoken, selected)
                ?: applyGeneralReplace(spoken, selected)

        if (rewritten.isNullOrBlank() || rewritten == selected) {
            return null
        }

        return "${snapshot.preText}$rewritten${snapshot.postText}".trim()
    }

    fun extractDeltaText(rawPayload: String): String {
        val directContentMatch = Regex("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").find(rawPayload)
        if (directContentMatch != null) {
            return decodeJsonString(directContentMatch.groupValues[1])
        }

        if (!rawPayload.contains("\"content\"")) {
            return ""
        }

        val parts =
            Regex("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
                .findAll(rawPayload)
                .map { decodeJsonString(it.groupValues[1]) }
                .toList()
        return parts.joinToString("")
    }

    private fun buildUserPrompt(
        sourceText: String,
        snapshot: SessionInputSnapshot,
        styleMode: TypeinkStyleMode,
    ): String {
        val styleInstructions = buildStyleModeInstructions(styleMode)
        if (snapshot.rewriteMode) {
            val targetLabel =
                if (snapshot.source == SessionRewriteSource.RECENT_COMMIT) {
                    "最近一次语音输入内容"
                } else {
                    "原选中文字"
                }
            return buildString {
                appendLine("任务：根据口语指令修改已选中的文字，并输出修改后的完整文本。")
                appendLine("口语指令：$sourceText")
                appendLine("$targetLabel：${snapshot.selectedText}")
                appendLine("选中文字前文：${snapshot.preText.ifBlank { "（空）" }}")
                appendLine("选中文字后文：${snapshot.postText.ifBlank { "（空）" }}")
                appendLine("要求：")
                appendLine("1. 先准确理解口语指令真正要改什么。")
                appendLine("2. 把口语指令应用到原选中文字上，不要把“改成”“删掉”等指令原样写出来。")
                appendLine("3. ${styleInstructions["selected"]}")
                appendLine("4. 自动去掉口语化噪声、语气词、重复和不必要赘述。")
                appendLine("5. ${styleInstructions["selected_detail"]}")
                appendLine("6. 如果原文已经自然，就尽量只做微调。")
                appendLine("7. 如果用户表达的是多个并列观点，整理成更清晰的列表结构。")
                appendLine("8. 正确处理中英混杂和专有名词。")
                appendLine("9. 输出最终完整文本，必须已经包含前文和后文。")
                append("10. 只输出最终文本。")
            }.trim()
        }

        return buildString {
            appendLine("任务：把语音识别草稿整理成适合直接写进输入框、也适合正式发布的最终文本。")
            appendLine("语音识别草稿：$sourceText")
            appendLine("要求：")
            appendLine("1. 先理解用户真正想表达的意思，再开始整理。")
            appendLine("2. 保留原意，不扩写，不编造信息，不改变核心观点。")
            appendLine("3. ${styleInstructions["single"]}")
            appendLine("4. 去掉明显口头语、语气词、重复、结巴和识别噪声。")
            appendLine("5. ${styleInstructions["single_detail"]}")
            appendLine("6. 如果内容明显是多个并列观点，整理成简洁列表。")
            appendLine("7. 正确处理中英混杂、专有名词和术语拼写，并在中英文之间补空格。")
            appendLine("8. 如果原句已经足够自然，就尽量少改。")
            appendLine("9. ${styleInstructions["example"]}")
            append("10. 只输出最终文本。")
        }.trim()
    }

    private fun buildStyleModeInstructions(styleMode: TypeinkStyleMode): Map<String, String> {
        return if (styleMode == TypeinkStyleMode.FORMAL) {
            mapOf(
                "selected" to "当前风格模式是“正式发布风格”，要把结果整理得专业、清晰、逻辑严密，适合直接发给客户、同事或公开发布。",
                "selected_detail" to "保持前后文连贯自然，必要时重组句子结构、提炼核心观点、补足逻辑连接词，使表达更流畅专业，但不要改变用户原意。",
                "single" to "当前风格模式是“正式发布风格”，把表达整理得清晰专业、逻辑严密、结构完整，适合正式场合使用，但不得改变原意或核心观点。",
                "single_detail" to "补充必要标点，修正明显语病，优化句式结构，提炼关键信息，合理分段，让结果更适合正式发送或发布。",
                "example" to "如果原句散漫无结构，应在不改原意的前提下，整理成逻辑清晰、层次分明的正式表达；如将零散想法组织成要点列表，使用恰当的连接词，使内容更具专业性。",
            )
        } else {
            mapOf(
                "selected" to "当前风格模式是“保留原话风格”，默认保持原文风格和说话口气，但要去掉明显累赘并修顺病句。",
                "selected_detail" to "保持前后文连贯自然，必要时补标点、删掉明显口头禅和重复，但不要无故改写成很书面的腔调，更不要把语气改得不像本人。",
                "single" to "当前风格模式是“保留原话风格”，默认保持用户原本的表达风格和口气，同时去掉明显累赘、重复和口误，并把病句修顺。",
                "single_detail" to "补充必要标点，修正明显语病，删掉无意义口头语，让句子自然易读，但不要顺手把语气升级成很正式的发布稿。",
                "example" to "例如：可以把散乱重复的话整理得更顺，但仍应保留“你测试一下就知道了”这种本人语气，不要擅自改成“实际测试后便知其原理”。",
            )
        }
    }
}

private fun applyPositionEdit(
    instruction: String,
    selectedText: String,
): String? {
    val firstMatch = firstCharRegex.find(instruction)
    if (firstMatch != null && selectedText.isNotEmpty()) {
        val replacement = extractReplacementText(firstMatch.groupValues[1])
        if (replacement.isNotBlank()) {
            return replacement + selectedText.drop(1)
        }
    }

    val lastMatch = lastCharRegex.find(instruction)
    if (lastMatch != null && selectedText.isNotEmpty()) {
        val replacement = extractReplacementText(lastMatch.groupValues[1])
        if (replacement.isNotBlank()) {
            return selectedText.dropLast(1) + replacement
        }
    }

    return null
}

private fun applyScopedReplace(
    instruction: String,
    selectedText: String,
): String? {
    val matches = scopedReplaceRegex.findAll(instruction).toList()
    for (match in matches.asReversed()) {
        val scope = normalizeFragment(match.groupValues[1])
        if (scope.isNotBlank() && scope != selectedText && !selectedText.contains(scope) && !scope.contains(selectedText)) {
            continue
        }

        val rewritten =
            rewriteSelectedText(
                selectedText = selectedText,
                oldFragment = match.groupValues[2],
                newFragment = match.groupValues[3],
                instruction = instruction,
            )
        if (rewritten != null) {
            return rewritten
        }
    }
    return null
}

private fun applyGeneralReplace(
    instruction: String,
    selectedText: String,
): String? {
    val matches = generalReplaceRegex.findAll(instruction).toList()
    for (match in matches.asReversed()) {
        val rewritten =
            rewriteSelectedText(
                selectedText = selectedText,
                oldFragment = match.groupValues[1],
                newFragment = match.groupValues[2],
                instruction = instruction,
            )
        if (rewritten != null) {
            return rewritten
        }
    }
    return null
}

private fun rewriteSelectedText(
    selectedText: String,
    oldFragment: String,
    newFragment: String,
    instruction: String,
): String? {
    val oldText = normalizeFragment(oldFragment)
    val newText = extractReplacementText(newFragment)
    if (oldText.isBlank() || newText.isBlank()) {
        return null
    }

    if (oldText in selectedText && oldText != selectedText) {
        return selectedText.replace(oldText, newText, ignoreCase = false)
    }

    if (oldText != selectedText) {
        return null
    }

    val preserved = extractPreservedSegment(instruction, selectedText)
    if (!preserved.isNullOrBlank()) {
        if (selectedText.startsWith(preserved)) {
            return preserved + newText
        }
        if (selectedText.endsWith(preserved)) {
            return newText + preserved
        }
    }

    return newText
}

private fun extractReplacementText(text: String): String {
    val tailQuoted = Regex("[\\\"'“‘]([^\\\"'“”‘’]+)[\\\"'”’]\\s*$").find(text)
    if (tailQuoted != null) {
        val candidate = normalizeFragment(tailQuoted.groupValues[1])
        if (candidate.isNotBlank()) return candidate
    }

    val quoted = quotedTextRegex.findAll(text).toList()
    if (quoted.isNotEmpty()) {
        val candidate = normalizeFragment(quoted.last().groupValues[1])
        if (candidate.isNotBlank()) return candidate
    }

    val candidate = normalizeFragment(text)
    if (candidate.isBlank()) {
        return ""
    }

    val descriptorMatch = Regex("([A-Za-z0-9\\u4e00-\\u9fff]{1,4})的([A-Za-z0-9\\u4e00-\\u9fff]{1,4})$").find(candidate)
    if (descriptorMatch != null) {
        return descriptorMatch.groupValues[2]
    }

    val spokenMatch = Regex(".+?那个([A-Za-z0-9\\u4e00-\\u9fff]{1,4})$").find(candidate)
    if (spokenMatch != null) {
        return spokenMatch.groupValues[1]
    }

    return candidate
}

private fun extractPreservedSegment(
    instruction: String,
    selectedText: String,
): String? {
    val candidates = mutableListOf<String>()
    for (start in selectedText.indices) {
        for (end in start + 1..selectedText.length) {
            val segment = selectedText.substring(start, end)
            if (segment.length >= selectedText.length) continue
            candidates += segment
        }
    }

    return candidates
        .sortedByDescending { it.length }
        .firstOrNull { segment ->
            instruction.contains("${segment}还是之前的$segment") ||
                instruction.contains("${segment}还是原来的$segment") ||
                instruction.contains("${segment}还是原来那个$segment")
        }
}

private fun normalizeFragment(text: String): String {
    var candidate = text.trim(*EDGE_PUNCTUATION.toCharArray())
    candidate = candidate.replace(Regex("^(这个|那个|就是|叫做)"), "")
    candidate = candidate.replace(Regex("(这个|那个)$"), "")
    return candidate.trim(*EDGE_PUNCTUATION.toCharArray())
}

private fun normalizeCommandText(text: String): String {
    return text.replace(Regex("[\\s，。！？；：、,.!?;:]+"), "")
}

private fun decodeJsonString(value: String): String {
    return value
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
