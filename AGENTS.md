# Simplex Admin Suite development

This repository contains the Admin Suite plugin (`Companion/AdminSuite`) and the optional Simplex Panel iOS client (`Panel`, `Shared`, `PanelWidgets`). Keep the existing checkout; cloud tasks are already isolated. Never create a worktree unless requested.

## Delivery and compatibility

- Plugin-only features should leave the iOS app unchanged whenever the existing API can serve them. Before changing endpoints, response shapes, authentication, confirmations or permissions, inspect both the Java API and `Panel/Core/SuiteAPI.swift` and the existing integration tests.
- Preserve API v1, existing fields/actions, keys, identifiers, stored data and permission boundaries. Prefer additive optional fields and capability detection. Do not promise compatibility for an untested live server.
- If an app change is needed, explain why and include it explicitly in the work. Build and test all IPAs through GitHub Actions; never attempt an iOS build on this Linux machine. Signing and installation still require the user's signing method.
- Deliver plugin changes as a downloadable JAR built by `.github/workflows/admin-suite.yml`, with checksums and complete source ZIP. On main, new plugin versions publish under `suite-vX.Y.Z`. Existing releases must never be overwritten. PR/manual builds provide artifacts even before a version bump.
- iOS changes use `.github/workflows/ios.yml` and `app-vX.Y.Z`. Plugin-only changes must not trigger macOS jobs. A plugin release must not replace the latest app release.
- Use focused commits and a clear PR describing behavior, compatibility, tests and artifacts. Do not merge or rename/delete the GitHub repository unless explicitly requested. Preserve unrelated user changes and never commit binaries, credentials or server data.

## Proportionate validation

- Do not run every suite after every edit. Select checks that cover the changed behavior, plus meaningful regression coverage for affected shared code. Documentation-only changes need no application build.
- Activate Java 25: `export JAVA_HOME=/workspace/toolchains/java25; export PATH="$JAVA_HOME/bin:$PATH"`.
- Plugin build: from `Companion/AdminSuite`, `python3 tools/build-real.py` (use `--offline` for already verified cached dependencies).
- Focused tests: `python3 tools/test-830.py --suite security --suite api`, adding `--suite database`, `--suite outbox` or `--suite release` for affected areas. Existing assertions remain enabled. The default without arguments runs all suites; use it for shared/unknown changes, toolchain changes and release-wide validation.
- CI always checks API/authentication compatibility on plugin changes. `tools/ci-test-scope.py` narrows only known isolated areas; unknown/shared changes run all existing harnesses. Extend selection rules only with evidence about coverage.
- SQL integration additionally uses the disposable MariaDB fixture and `TEST_DATABASE=1`; never use production. Do not run the SQL suite without that flag and claim SQL integration passed.
- iOS simulator tests and device builds run in Actions only when app inputs or the iOS workflow change (or on explicit manual dispatch). Add or target tests for new behavior; expand to broader suites only for shared changes, failures or unresolved concerns.
- Keep TLS and artifact checksum verification enabled. Report passed, failed and unrun checks separately. After pushing a PR, inspect its Actions run and provide links to the downloadable artifacts once available.
