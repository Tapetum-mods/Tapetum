# Contributing to Tapetum

## Branches

Tapetum follows the same versioned-branch model as Iris:

| Branch | Purpose |
|---|---|
| `26.1` | Default branch and Minecraft 26.1 line |
| `26.2` | Minecraft 26.2 line |
| `future` | Cross-version work that is not ready for a release line |
| `main` | Existing integration branch; do not use it for version-specific fixes |

A change that only affects one Minecraft version goes directly to that version's branch.
A change that is shared by multiple versions should be developed on `future`, then backported
or merged into each affected version branch.

## Pull requests

- Target the oldest affected version branch first.
- Keep version-specific Minecraft API changes out of `common/` and shared code when possible.
- Run `./gradlew clean build --console=plain` before opening a pull request.
- Do not merge a change into `26.2` and assume that `26.1` receives it automatically.
- Use a separate `feature/*`, `fix/*`, or `hotfix/*` branch for non-trivial work.

## Release flow

1. Open a pull request against the affected version branch.
2. Run the full clean build and the focused regression tests.
3. Merge the pull request after review.
4. Tag releases from the corresponding version branch.

The branch `26.1` remains the repository default so GitHub opens the active supported line by
default, matching Iris's repository layout.
