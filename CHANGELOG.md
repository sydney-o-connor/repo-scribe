# Changelog

All notable changes to repo-scribe are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project intends to follow [Semantic Versioning](https://semver.org/) once it has a
tagged release.

## [Unreleased]

### Changed
- The README path argument is now optional. `kotlin scan-repo.main.kts <repo>` alone
  now creates or updates `<repo>/README.md`. Previously the default `"README.md"` was
  resolved against the current working directory rather than the repo root, so running
  from anywhere other than the repo itself silently wrote (or looked for) the README
  in the wrong place. An explicit second argument still works exactly as before.

### Planned
- Naming-convention and `!!`-usage lint rules
- Swap regex parsing for a real parser (`kotlinx-ast` or compiler PSI) to remove the
  multi-line-signature and nested-generics limitations
- First tagged release + GitHub Marketplace listing

## [0.2.0] - 2026-09-08

### Added
- Nested member attribution: functions and properties inside a class/interface/object
  are now correctly attributed to their owner, instead of only top-level declarations
  being detected
- Real signature extraction: function parameter lists and return types, property
  types, and superclass/interface lists (e.g. `class Rectangle(...) : Shape`)
- KDoc summaries: the first line of a declaration's doc comment is pulled into the
  generated README instead of just a documented/undocumented flag
- Recursive rendering of nested types (e.g. a `companion object`'s own members now
  show up under it, instead of the companion object appearing as a bare name)
- `key` / `ownerKey` fields in the JSON report for unambiguous nesting (see Fixed)

### Fixed
- Nested-member grouping previously matched by declaration *name*, which merged
  members from unrelated classes that happened to share a nested name — most commonly
  two different classes each having a `Companion` object. Now grouped by a unique
  `path:line` key per declaration.
- Multi-line constructor parameter lists (e.g. a data class with one field per line)
  were being parsed line-by-line as bogus top-level properties, with trailing inline
  comments leaking into the captured type string. Fixed by tracking paren depth and
  suppressing declaration matching while inside an unclosed multi-line parameter group.

## [0.1.0] - 2026-09-08

### Added
- Initial project scaffold: `scan-repo.main.kts` script, `Dockerfile`, `action.yml`,
  and an example `.github/workflows/repo-scribe.yml`
- Repo scan: walks `.kt`/`.kts` files, detects top-level `class`/`interface`/`object`/
  `fun` declarations, flags missing KDoc on public API, and collects `TODO`/`FIXME`
  comments
- JSON report output (`repo-scribe-report.json`)
- Idempotent README section injection via `<!-- REPO-SCRIBE:START/END -->` markers
- GitHub Actions `::warning::`/`::notice::` annotations for CI

[Unreleased]: https://github.com/your-org/repo-scribe/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/your-org/repo-scribe/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/your-org/repo-scribe/releases/tag/v0.1.0