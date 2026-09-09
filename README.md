# repo-scribe

A Kotlin-scripted GitHub Action that scans a repo's `.kt`/`.kts` files, generates a
"Repo overview" section in your README, flags public declarations missing KDoc, and
surfaces `TODO`/`FIXME` markers as PR annotations.

It's built as a single [Kotlin script](https://kotlinlang.org/docs/custom-script-deps-tutorial.html)
(`scan-repo.main.kts`) with no external dependencies, wrapped in a Docker-based
GitHub Action — no Gradle project, no build step, just a script that runs top to bottom.

## What it does

1. Walks the repo looking for `.kt`/`.kts` files
2. For each file, extracts top-level `class`/`interface`/`object`/`fun` declarations
   (skipping `private`/`internal`) and checks whether each has a preceding `/** ... */` block
3. Collects `// TODO` and `// FIXME` comments
4. Writes a machine-readable `repo-scribe-report.json`
5. Injects a generated Markdown section into `README.md`, bounded by
   `<!-- REPO-SCRIBE:START -->` / `<!-- REPO-SCRIBE:END -->` markers (safe to re-run —
   it replaces the section in place rather than duplicating it)
6. In CI, emits `::warning::` / `::notice::` [workflow command annotations](https://docs.github.com/en/actions/using-workflows/workflow-commands-for-github-actions)
   so undocumented public API and TODOs show up inline on the PR "Files changed" tab

## Try it locally

You need the Kotlin compiler (which bundles the `kotlin` script runner). Grab a release
directly from GitHub — no separate installer needed:

```bash
curl -sL -o kotlin.zip \
  "https://github.com/JetBrains/kotlin/releases/download/v2.0.20/kotlin-compiler-2.0.20.zip"
unzip -q kotlin.zip -d /tmp
export PATH="/tmp/kotlinc/bin:$PATH"
```

Then run it against any repo:

```bash
kotlin scan-repo.main.kts /path/to/some/repo /path/to/some/repo/README.md
```

Run it again with `GITHUB_ACTIONS=true` set to preview the CI annotation format:

```bash
GITHUB_ACTIONS=true kotlin scan-repo.main.kts . README.md
```

## Using it as a GitHub Action

See `.github/workflows/repo-scribe.yml` for a full example. The short version:

```yaml
- uses: actions/checkout@v4
- uses: ./ # or your-org/repo-scribe@v1 once published
  with:
    repo-root: "."
    readme-path: "README.md"
```

**Note:** the Docker image wasn't build-tested as part of scaffolding this (no Docker
daemon available in the environment it was built in) — the script itself *was* run and
verified against a sample repo, but do a `docker build .` locally before you trust the
action end-to-end.

## Design notes / why it's built this way

- **No dependencies at runtime.** Regex + `java.io.File` instead of a real Kotlin parser
  or `kotlinx.serialization`. This keeps the Docker image small and avoids Maven
  dependency resolution slowing down every CI run. See "Next steps" below for how to
  outgrow this deliberately.
- **Top-level declarations only.** Nested/member declarations (methods inside a class)
  are skipped for now — regex-parsing indentation-sensitive nested Kotlin is a fast way
  to write bugs. A real parser (see below) removes this limitation properly.
- **Idempotent README injection.** Marker comments mean re-running the action on every
  PR updates the same section instead of appending duplicates forever.

## Next steps (this is where the "learning Kotlin scripting" part continues)

1. **Add a real dependency.** Swap the hand-rolled JSON writer for
   `kotlinx.serialization`, using `main-kts`'s `@file:DependsOn(...)` to pull it in at
   script-run time. This is the main new mental model versus regular Gradle-built Kotlin.
2. **Parse instead of regex.** Look at [`kotlinx-ast`](https://github.com/kotlinx/ast) or
   the compiler's own PSI to correctly find nested declarations, constructors, and
   properties — the regex approach will always have edge cases (multi-line signatures,
   declarations inside `companion object`, etc).
3. **More lint rules.** Naming conventions, file length, `!!` usage count, unused
   imports — anything a first pass through a codebase should catch.
4. **Publish to the Marketplace.** Tag a release, add a `branding` icon (already
   scaffolded in `action.yml`), and it's installable by anyone via
   `uses: your-org/repo-scribe@v1`.
   