#!/usr/bin/env kotlin
/*
 * repo-scribe: walks a Kotlin repo, summarizes its structure, checks basic doc hygiene,
 * and (a) writes a JSON report, (b) injects a generated section into README.md,
 * (c) emits GitHub Actions warning annotations for CI.
 *
 * Usage:
 *   kotlin scan-repo.main.kts [repoRoot] [readmePath]
 *
 * Defaults: repoRoot = ".", readmePath = "./README.md"
 *
 * No external dependencies on purpose -- this keeps the Docker image small and the
 * script fast to start in CI (no Maven dependency resolution at runtime). If you want
 * to learn main-kts dependency resolution, try adding:
 *   @file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
 * and swapping the hand-rolled JSON writer below for real serialization.
 */

import java.io.File

// ---------- data model ----------

data class Declaration(
    val kind: String,      // "class" | "interface" | "object" | "fun"
    val name: String,
    val line: Int,
    val documented: Boolean
)

data class TodoItem(val line: Int, val tag: String, val text: String)

data class FileReport(
    val path: String,
    val packageName: String,
    val declarations: List<Declaration>,
    val todos: List<TodoItem>
)

// ---------- scanning ----------

val declRegex = Regex("""^\s*(?:public\s+)?(?:(private|internal)\s+)?(?:(?:open|abstract|final|sealed|data|inline|value|enum)\s+)*(class|interface|object|fun)\s+(\w+)""")
val packageRegex = Regex("""^\s*package\s+([\w.]+)""")
val todoRegex = Regex("""//\s*(TODO|FIXME)\s*:?\s*(.*)""")

fun scanFile(file: File): FileReport {
    val lines = file.readLines()
    var packageName = ""
    val decls = mutableListOf<Declaration>()
    val todos = mutableListOf<TodoItem>()

    for ((idx, line) in lines.withIndex()) {
        packageRegex.find(line)?.let { packageName = it.groupValues[1] }

        todoRegex.find(line)?.let {
            todos += TodoItem(idx + 1, it.groupValues[1], it.groupValues[2].trim())
        }

        val match = declRegex.find(line) ?: continue
        val visibility = match.groupValues[1]
        if (visibility == "private" || visibility == "internal") continue
        // only count top-level declarations (no leading indentation) to keep this simple --
        // nested/member declarations are a natural v2 improvement.
        if (line != line.trimStart()) continue

        if (line != line.trimStart()) continue // indented -> nested/member declaration, skip

        val kind = match.groupValues[2]
        val name = match.groupValues[3]

        // "documented" = the nearest preceding non-blank line closes a /** ... */ block
        var i = idx - 1
        while (i >= 0 && lines[i].isBlank()) i--
        val documented = i >= 0 && lines[i].trim().endsWith("*/")

        decls += Declaration(kind, name, idx + 1, documented)
    }

    return FileReport(file.path, packageName, decls, todos)
}

// ---------- main ----------

val repoRoot = File(args.getOrElse(0) { "." })
val readmePath = File(args.getOrElse(1) { "README.md" })
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
            val msg = "Public ${decl.kind} '${decl.name}' has no KDoc comment"
            if (isCi) {
                println("::warning file=${report.path},line=${decl.line}::$msg")
            } else {
                println("  [undocumented] ${report.path}:${decl.line} -- $msg")
            }
        }
    }
    for (todo in report.todos) {
        val msg = "${todo.tag}: ${todo.text}".trim()
        if (isCi) {
            println("::notice file=${report.path},line=${todo.line}::$msg")
        } else {
            println("  [${todo.tag.lowercase()}] ${report.path}:${todo.line} -- ${todo.text}")
        }
    }
}

// ---------- output: JSON report ----------

fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

fun declToJson(d: Declaration) =
    """{"kind":"${d.kind}","name":"${jsonEscape(d.name)}","line":${d.line},"documented":${d.documented}}"""

fun todoToJson(t: TodoItem) =
    """{"line":${t.line},"tag":"${t.tag}","text":"${jsonEscape(t.text)}"}"""

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

val reportFile = File(repoRoot, "repo-scribe-report.json")
reportFile.writeText(json)
println("wrote ${reportFile.path}")

// ---------- output: README section ----------

val startMarker = "<!-- REPO-SCRIBE:START -->"
val endMarker = "<!-- REPO-SCRIBE:END -->"

val moduleTable = buildString {
    appendLine("| Package | Files | Public API | Documented |")
    appendLine("|---|---|---|---|")
    for (pkg in packages) {
        val pkgReports = reports.filter { it.packageName == pkg }
        val pkgDecls = pkgReports.flatMap { it.declarations }
        val pkgDocumented = pkgDecls.count { it.documented }
        appendLine("| `$pkg` | ${pkgReports.size} | ${pkgDecls.size} | $pkgDocumented/${pkgDecls.size} |")
    }
}

val generatedSection = buildString {
    appendLine(startMarker)
    appendLine("<!-- This section is auto-generated by repo-scribe. Do not edit by hand. -->")
    appendLine()
    appendLine("## Repo overview")
    appendLine()
    appendLine("Scanned ${reports.size} Kotlin file(s) across ${packages.size} package(s). " +
        "Doc coverage: $coveragePct% ($documentedCount/${allDecls.size} public declarations).")
    appendLine()
    append(moduleTable)
    appendLine()
    appendLine(endMarker)
}

val existing = if (readmePath.exists()) readmePath.readText() else "# ${repoRoot.canonicalFile.name}\n\n"
val newContent = if (existing.contains(startMarker) && existing.contains(endMarker)) {
    val before = existing.substringBefore(startMarker)
    val after = existing.substringAfter(endMarker)
    before + generatedSection + after
} else {
    existing.trimEnd() + "\n\n" + generatedSection
}

readmePath.writeText(newContent)
println("updated ${readmePath.path}")
