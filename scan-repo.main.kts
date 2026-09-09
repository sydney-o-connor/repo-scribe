#!/usr/bin/env kotlin
/*
 * repo-scribe: walks a Kotlin repo, summarizes its structure (classes, interfaces,
 * functions, properties -- including nested members and inheritance), checks basic
 * doc hygiene, and (a) writes a JSON report, (b) injects a generated section into
 * README.md, (c) emits GitHub Actions annotations for CI.
 *
 * Usage:
 *   kotlin scan-repo.main.kts [repoRoot] [readmePath]
 *
 * Parsing approach: regex + a brace-depth stack, not a real parser. This is enough to
 * handle typical, conventionally-formatted Kotlin (one declaration per line, opening
 * brace on the same line) but will misparse: multi-line signatures, nested generics
 * like `<T : List<String>>`, and unconventional brace placement. See README "Next
 * steps" for how to swap this for a real parser (kotlinx-ast or compiler PSI).
 */

import java.io.File

// ---------- data model ----------

data class Declaration(
    val kind: String,          // "class" | "interface" | "object" | "fun" | "val" | "var"
    val name: String,
    val line: Int,
    val documented: Boolean,
    val summary: String?,      // first line of the KDoc block, if documented
    val signature: String?,    // "(params): ReturnType" for fun, ": Type" for val/var, "supertypes" for types
    val ownerClass: String?,   // enclosing class/interface/object name, or null if top-level (display only)
    val key: String,           // unique id ("path:line") -- used to correctly group nested members
    val ownerKey: String?      // key of the enclosing type declaration, or null if top-level
)

data class TodoItem(val line: Int, val tag: String, val text: String)

data class FileReport(
    val path: String,
    val packageName: String,
    val declarations: List<Declaration>,
    val todos: List<TodoItem>
)

private data class Frame(val kind: String, val name: String?, val key: String?) // kind: "type" | "function" | "block"

// ---------- regexes ----------

val packageRegex = Regex("""^\s*package\s+([\w.]+)""")
val todoRegex = Regex("""//\s*(TODO|FIXME)\s*:?\s*(.*)""")

val typeDeclRegex = Regex(
    """^\s*(?:(private|internal)\s+)?(?:(?:open|abstract|final|sealed|data|inline|value|enum|annotation|companion)\s+)*(class|interface|object)(?:\s+(\w+)(?:\s*<[^>]*>)?)?"""
)
val funDeclRegex = Regex(
    """^\s*(?:(private|internal)\s+)?(?:(?:open|override|abstract|final|inline|suspend|operator|infix|tailrec|external)\s+)*fun\s+(?:<[^>]*>\s+)?(\w+)\s*\(([^)]*)\)\s*(?::\s*([^{=]+?))?\s*(\{|=|$)"""
)
val propDeclRegex = Regex(
    """^\s*(?:(private|internal)\s+)?(?:(?:open|override|const|lateinit|abstract)\s+)*(val|var)\s+(\w+)\s*(?::\s*([^={]+?))?\s*(=|\{|$)"""
)

// finds "(...)"-wrapped primary constructor + ": Supertype, Other" after a type declaration match
fun findSupertypesText(line: String, startIdx: Int): String? {
    var i = startIdx
    while (i < line.length && line[i].isWhitespace()) i++
    if (i < line.length && line[i] == '(') {
        var depth = 0
        while (i < line.length) {
            when (line[i]) {
                '(' -> depth++
                ')' -> { depth--; i++; if (depth == 0) return findSupertypesAfterParen(line, i) }
            }
            i++
        }
        return null
    }
    return findSupertypesAfterParen(line, i)
}

fun findSupertypesAfterParen(line: String, from: Int): String? {
    var i = from
    while (i < line.length && line[i].isWhitespace()) i++
    if (i < line.length && line[i] == ':') {
        val rest = line.substring(i + 1)
        val braceIdx = rest.indexOf('{')
        val text = if (braceIdx >= 0) rest.substring(0, braceIdx) else rest
        return text.trim().ifBlank { null }
    }
    return null
}

// ---------- scanning ----------

fun scanFile(file: File): FileReport {
    val lines = file.readLines()
    var packageName = ""
    val decls = mutableListOf<Declaration>()
    val todos = mutableListOf<TodoItem>()
    val frameStack = ArrayDeque<Frame>()
    var parenDepth = 0 // tracks multi-line "(" ... ")" groups (e.g. a constructor param list
                        // spread across lines) so we don't misread each inner line as a
                        // standalone top-level declaration

    fun kdocSummaryAt(idx: Int): Pair<Boolean, String?> {
        var i = idx - 1
        while (i >= 0 && lines[i].isBlank()) i--
        if (i < 0 || !lines[i].trim().endsWith("*/")) return false to null
        // walk upward collecting the KDoc block, then take its first meaningful line
        var start = i
        while (start >= 0 && !lines[start].trim().startsWith("/**")) start--
        if (start < 0) return true to null
        val firstContentLine = (start + 1..i)
            .map { lines[it].trim().removePrefix("*").trim() }
            .firstOrNull { it.isNotBlank() && it != "*/" }
            ?.removeSuffix("*/")
            ?.trim()
        return true to firstContentLine
    }

    for ((idx, line) in lines.withIndex()) {
        packageRegex.find(line)?.let { packageName = it.groupValues[1] }
        todoRegex.find(line)?.let { todos += TodoItem(idx + 1, it.groupValues[1], it.groupValues[2].trim()) }

        val owner = frameStack.lastOrNull()
        val isLocal = owner != null && owner.kind != "type" // inside a function/block body -> not public API
        val lineKey = "${file.path}:${idx + 1}"

        var pushKind: String? = null
        var pushName: String? = null
        var pushKey: String? = null

        val insideUnclosedParen = parenDepth > 0
        val typeMatch = if (insideUnclosedParen) null else typeDeclRegex.find(line)
        val funMatch = if (typeMatch == null && !insideUnclosedParen) funDeclRegex.find(line) else null
        val propMatch = if (typeMatch == null && funMatch == null && !insideUnclosedParen) propDeclRegex.find(line) else null

        when {
            typeMatch != null -> {
                val visibility = typeMatch.groupValues[1]
                val kind = typeMatch.groupValues[2]
                val name = typeMatch.groupValues[3].ifBlank { if (kind == "object") "Companion" else "<anonymous>" }
                pushKind = "type"; pushName = name; pushKey = lineKey
                if (visibility != "private" && visibility != "internal" && !isLocal) {
                    val (documented, summary) = kdocSummaryAt(idx)
                    val supertypes = findSupertypesText(line, typeMatch.range.last + 1)
                    decls += Declaration(kind, name, idx + 1, documented, summary, supertypes, owner?.name, lineKey, owner?.key)
                }
            }
            funMatch != null -> {
                val visibility = funMatch.groupValues[1]
                val name = funMatch.groupValues[2]
                val params = funMatch.groupValues[3].trim()
                val returnType = funMatch.groupValues[4].trim().ifBlank { null }
                pushKind = "function"; pushName = name; pushKey = lineKey
                if (visibility != "private" && visibility != "internal" && !isLocal) {
                    val (documented, summary) = kdocSummaryAt(idx)
                    val signature = "(${params})" + (returnType?.let { ": $it" } ?: "")
                    decls += Declaration("fun", name, idx + 1, documented, summary, signature, owner?.name, lineKey, owner?.key)
                }
            }
            propMatch != null -> {
                val visibility = propMatch.groupValues[1]
                val kind = propMatch.groupValues[2]
                val name = propMatch.groupValues[3]
                val type = propMatch.groupValues[4].trim().ifBlank { null }
                if (visibility != "private" && visibility != "internal" && !isLocal) {
                    val (documented, summary) = kdocSummaryAt(idx)
                    val signature = type?.let { ": $it" }
                    decls += Declaration(kind, name, idx + 1, documented, summary, signature, owner?.name, lineKey, owner?.key)
                }
            }
        }

        val net = line.count { it == '{' } - line.count { it == '}' }
        if (net > 0) {
            val frameKind = pushKind ?: "block"
            repeat(net) { frameStack.addLast(Frame(frameKind, pushName, pushKey)) }
        } else if (net < 0) {
            repeat(minOf(-net, frameStack.size)) { frameStack.removeLast() }
        }

        parenDepth = maxOf(0, parenDepth + line.count { it == '(' } - line.count { it == ')' })
    }

    return FileReport(file.path, packageName, decls, todos)
}

// ---------- main ----------

val repoRoot = File(args.getOrElse(0) { "." })
val readmePath = args.getOrNull(1)?.let { File(it) } ?: File(repoRoot, "README.md")
val isCi = System.getenv("GITHUB_ACTIONS") == "true"

val kotlinFiles = repoRoot.walkTopDown()
    .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
    .filterNot { it.path.contains("/build/") || it.path.contains("/.git/") }
    .toList()

val reports = kotlinFiles.map { scanFile(it) }
val allDecls = reports.flatMap { it.declarations }
val documentedCount = allDecls.count { it.documented }
val coveragePct = if (allDecls.isEmpty()) 100 else (documentedCount * 100 / allDecls.size)
val totalTodos = reports.sumOf { it.todos.size }
val packages = reports.map { it.packageName }.filter { it.isNotBlank() }.distinct().sorted()

// ---------- output: human/CI report ----------

println("repo-scribe: scanned ${kotlinFiles.size} Kotlin file(s) across ${packages.size} package(s)")
println("  public declarations: ${allDecls.size}, documented: $documentedCount ($coveragePct%)")
println("  TODO/FIXME markers: $totalTodos")

for (report in reports) {
    for (decl in report.declarations) {
        if (!decl.documented) {
            val label = if (decl.ownerClass != null) "${decl.ownerClass}.${decl.name}" else decl.name
            val msg = "Public ${decl.kind} '$label' has no KDoc comment"
            if (isCi) println("::warning file=${report.path},line=${decl.line}::$msg")
            else println("  [undocumented] ${report.path}:${decl.line} -- $msg")
        }
    }
    for (todo in report.todos) {
        val msg = "${todo.tag}: ${todo.text}".trim()
        if (isCi) println("::notice file=${report.path},line=${todo.line}::$msg")
        else println("  [${todo.tag.lowercase()}] ${report.path}:${todo.line} -- ${todo.text}")
    }
}

// ---------- output: JSON report ----------

fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
fun jsonStr(s: String?) = if (s == null) "null" else "\"${jsonEscape(s)}\""

fun declToJson(d: Declaration) = """{"kind":"${d.kind}","name":"${jsonEscape(d.name)}","line":${d.line},"documented":${d.documented},"summary":${jsonStr(d.summary)},"signature":${jsonStr(d.signature)},"ownerClass":${jsonStr(d.ownerClass)},"key":"${jsonEscape(d.key)}","ownerKey":${jsonStr(d.ownerKey)}}"""
fun todoToJson(t: TodoItem) = """{"line":${t.line},"tag":"${t.tag}","text":"${jsonEscape(t.text)}"}"""
fun fileReportToJson(r: FileReport) = """
    {
      "path": "${jsonEscape(r.path)}",
      "package": "${jsonEscape(r.packageName)}",
      "declarations": [${r.declarations.joinToString(",") { declToJson(it) }}],
      "todos": [${r.todos.joinToString(",") { todoToJson(it) }}]
    }
""".trimIndent()

val json = """
{
  "filesScanned": ${kotlinFiles.size},
  "packages": [${packages.joinToString(",") { "\"${jsonEscape(it)}\"" }}],
  "publicDeclarations": ${allDecls.size},
  "documentedDeclarations": $documentedCount,
  "docCoveragePercent": $coveragePct,
  "todoCount": $totalTodos,
  "files": [${reports.joinToString(",") { fileReportToJson(it) }}]
}
""".trimIndent()

File(repoRoot, "repo-scribe-report.json").writeText(json)
println("wrote ${File(repoRoot, "repo-scribe-report.json").path}")

// ---------- output: README section ----------

val startMarker = "<!-- REPO-SCRIBE:START -->"
val endMarker = "<!-- REPO-SCRIBE:END -->"

val typeKinds = setOf("class", "interface", "object")

fun renderMember(d: Declaration, indent: String): String {
    val sig = when (d.kind) {
        "fun" -> "fun ${d.name}${d.signature ?: "()"}"
        "val", "var" -> "${d.kind} ${d.name}${d.signature ?: ""}"
        else -> d.name
    }
    val doc = d.summary?.let { " -- $it" } ?: ""
    return "$indent- `$sig`$doc"
}

// Renders a type and recurses into its members, including nested types (companion
// objects, inner classes) so their members show up instead of a bare name.
fun StringBuilder.renderType(type: Declaration, pkgDecls: List<Declaration>, indent: String, seen: MutableSet<String>) {
    if (!seen.add(type.key)) return // guard against accidental cycles
    val extends = type.signature?.let { " : $it" } ?: ""
    appendLine("$indent**${type.kind} ${type.name}**$extends")
    type.summary?.let { appendLine("$indent  $it") }
    val members = pkgDecls.filter { it.ownerKey == type.key } // key-based, not name-based -- avoids
    // merging two unrelated "Companion" objects (or any other repeated nested name) from different classes.
    for (m in members) {
        if (m.kind in typeKinds) {
            renderType(m, pkgDecls, "$indent  ", seen)
        } else {
            appendLine(renderMember(m, "$indent  "))
        }
    }
    appendLine()
}

val narrative = buildString {
    for (pkg in packages) {
        appendLine("### `$pkg`")
        appendLine()
        val pkgDecls = reports.filter { it.packageName == pkg }.flatMap { it.declarations }
        val topLevelTypes = pkgDecls.filter { it.ownerKey == null && it.kind in typeKinds }
        val topLevelFns = pkgDecls.filter { it.ownerKey == null && it.kind == "fun" }
        val topLevelProps = pkgDecls.filter { it.ownerKey == null && it.kind in setOf("val", "var") }
        val seen = mutableSetOf<String>()

        for (type in topLevelTypes) renderType(type, pkgDecls, "", seen)

        if (topLevelFns.isNotEmpty() || topLevelProps.isNotEmpty()) {
            appendLine("**Top-level:**")
            for (fn in topLevelFns) appendLine(renderMember(fn, "  "))
            for (p in topLevelProps) appendLine(renderMember(p, "  "))
            appendLine()
        }
    }
}

val generatedSection = buildString {
    appendLine(startMarker)
    appendLine("<!-- This section is auto-generated by repo-scribe. Do not edit by hand. -->")
    appendLine()
    appendLine("## Repo overview")
    appendLine()
    appendLine("Scanned ${reports.size} Kotlin file(s) across ${packages.size} package(s). " +
        "Doc coverage: $coveragePct% ($documentedCount/${allDecls.size} public declarations, $totalTodos TODO/FIXME open).")
    appendLine()
    append(narrative)
    appendLine(endMarker)
}

val existing = if (readmePath.exists()) readmePath.readText() else "# ${repoRoot.canonicalFile.name}\n\n"
val newContent = if (existing.contains(startMarker) && existing.contains(endMarker)) {
    existing.substringBefore(startMarker) + generatedSection + existing.substringAfter(endMarker)
} else {
    existing.trimEnd() + "\n\n" + generatedSection
}
readmePath.writeText(newContent)
println("updated ${readmePath.path}")