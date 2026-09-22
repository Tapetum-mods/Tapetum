## Target branch

- [ ] `26.1`
- [ ] `26.2`
- [ ] `26.3` experimental
- [ ] `future`

Choose the affected Minecraft line, or `future` for shared development. Do not use the default
branch automatically. See [branch policy](../CONTRIBUTING.md#branches).

## Change type

- [ ] Version-specific fix
- [ ] Shared/common change
- [ ] Feature
- [ ] Documentation

## Validation

- [ ] `./gradlew clean build --console=plain`
- [ ] Focused regression tests, if applicable
- [ ] Backport or forward-port pull requests are linked for other affected version branches,
      or the reason they are not needed is documented

## Notes

Describe any Minecraft-version-specific behavior, backport requirements, or known limitations.
