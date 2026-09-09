# repo-scribe

A Kotlin-scripted GitHub Action that scans a repo's `.kt`/`.kts` files, generates a
"Repo overview" section in your README, flags public declarations missing KDoc, and
surfaces `TODO`/`FIXME` markers as PR annotations.

It's built as a single [Kotlin script](https://kotlinlang.org/docs/custom-script-deps-tutorial.html)
(`scan-repo.main.kts`) with no external dependencies, wrapped in a Docker-based
GitHub Action — no Gradle project, no build step, just a script that runs top to bottom.

See [CHANGELOG.md](./CHANGELOG.md) for what's changed between versions.

## What it does

1. Walks the repo looking for `.kt`/`.kts` files
2. Parses `class`/`interface`/`object`/`fun`/`val`/`var` declarations (skipping
   `private`/`internal`), using a brace-and-paren-depth stack to correctly attribute
   **nested members** to their enclosing class — including nested types like companion
   objects, which are matched by a unique key rather than by name (so two classes that
   each have a `Companion` object don't get merged)
3. Extracts real signatures — parameter lists, return types, property types, and
   superclass/interface lists (`class Rectangle(...) : Shape`)
4. Pulls the first line of each declaration's KDoc block as a summary, instead of just
   a documented/undocumented flag
5. Collects `// TODO` and `// FIXME` comments
6. Writes a machine-readable `repo-scribe-report.json`
7. Injects a generated Markdown section into `README.md` — a real narrative per
   package: types, their inherited interfaces, their KDoc summaries, and their member
   signatures, nested recursively — bounded by `<!-- REPO-SCRIBE:START -->` /
   `<!-- REPO-SCRIBE:END -->` markers (safe to re-run — replaces the section in place)
8. In CI, emits `::warning::` / `::notice::` [workflow command annotations](https://docs.github.com/en/actions/using-workflows/workflow-commands-for-github-actions)
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
kotlin scan-repo.main.kts /path/to/some/repo
```

That's it — it updates `README.md` at the root of that repo, creating one if it
doesn't exist yet. Pass a second argument if you want to write somewhere else instead:

```bash
kotlin scan-repo.main.kts /path/to/some/repo /path/to/some/repo/docs/OVERVIEW.md
```

Run it again with `GITHUB_ACTIONS=true` set to preview the CI annotation format:

```bash
GITHUB_ACTIONS=true kotlin scan-repo.main.kts .
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

- **No dependencies at runtime.** Regex + `java.io.File`, not a real Kotlin parser or
  `kotlinx.serialization`. Keeps the Docker image small and avoids Maven dependency
  resolution slowing down every CI run. See "Next steps" for outgrowing this.
- **Brace/paren-depth tracking, not indentation.** A small stack tracks which
  class/interface/object body each line is inside, so member functions and properties
  get attributed to the right owner even when nested (companion objects, inner
  classes). Declarations are matched by a unique `path:line` key internally, not by
  name — so two unrelated classes each having a `Companion` object don't collide.
- **Idempotent README injection.** Marker comments mean re-running the action on every
  PR updates the same section instead of appending duplicates forever.

### Known limitations (regex parsing has real edges)

- **Multi-line signatures aren't parsed.** A function or class header that spans
  several lines (long parameter lists broken across lines) won't have its signature
  captured correctly — the paren-depth tracker prevents it from being *misread* as
  something else, but it also won't be read as a full declaration. Single-line
  constructor calls and signatures work fine.
- **Nested generics can confuse the type regex** — `<T : List<String>>` has two `>`
  characters, and the regex only strips one level of `<...>`. Shallow generics
  (`List<Shape>`, `Box<T>`) are fine.
- **Brace counting is naive.** A `{` or `}` inside a string literal or comment would
  throw off the depth tracker. Rare in practice, but it's a real gap versus a proper
  parser.

## Next steps (this is where the "learning Kotlin scripting" part continues)

1. **Add a real dependency.** Swap the hand-rolled JSON writer for
   `kotlinx.serialization`, using `main-kts`'s `@file:DependsOn(...)` to pull it in at
   script-run time. This is the main new mental model versus regular Gradle-built Kotlin.
2. **Swap regex for a real parser.** This is the fix for every limitation listed above
   at once. Look at [`kotlinx-ast`](https://github.com/kotlinx/ast), or use the Kotlin
   compiler's own embeddable PSI classes (already sitting in `kotlin-compiler.jar`
   inside any `kotlinc` distribution) to get a real syntax tree instead of line-by-line
   guessing.
3. **More lint rules.** Naming conventions, file length, `!!` usage count, unused
   imports — anything a first pass through a codebase should catch.
4. **Publish to the Marketplace.** Tag a release, add a `branding` icon (already
   scaffolded in `action.yml`), and it's installable by anyone via
   `uses: your-org/repo-scribe@v1`.