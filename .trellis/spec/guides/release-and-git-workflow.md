# Release And Git Workflow

> **Purpose**: Define the project's release flow, note format, and Git/GitHub boundaries.

---

## Scope

Use this guide when preparing a public release for the Android project or when
changing the local release scripts under `.release_local/release/`.

This guide is about the repository's release process, not Trellis task
bookkeeping.

## Release Flow

1. Read the local release process in `.release_local/README.md` before starting
   a release. Treat that file as the operational source of truth for local
   release commands.
2. Run the local release script from the repository root:
   `powershell -ExecutionPolicy Bypass -File .\.release_local\release\build_release.ps1`
3. Let the script sync version files and build release artifacts.
4. Prepare a handwritten `RELEASE_NOTES_vX.Y.Z.md` file.
5. Publish the GitHub release with `-PublishGitHub` and the handwritten notes
   file.

## Release Notes Format

Write the release notes in English first, then immediately below that write a
Chinese version in the same file.

Use this structure:

1. `Major changes`
   - Paste the commit records since the previous version, usually from the
     previous semver tag to the release commit.
2. `Main new features`
   - Describe the user-visible capabilities those commits introduced.
3. `Bug fixes`
   - Describe which bugs were fixed and whether they are fully resolved.

## Git And GitHub Boundary

- Only version/source changes belong in git release commits.
- Do not commit `.release_local/` or `release_artifacts/`.
- Create the git tag after the release commit is ready.
- Push the tag together with the release branch when publishing.
- Use GitHub release publishing only after the handwritten notes file exists.
- Start the GitHub release flow only after artifacts and the final bilingual
  release notes file are ready.

## Safety Rules

- Do not generate public release notes from commit subjects alone.
- Do not machine-translate the English section into the Chinese section word for
  word.
- Do not publish a GitHub release without handwritten notes.

## Practical Notes

- The version sync script updates `VERSION` and `android/LXB-Ignition/app/build.gradle.kts`.
- The release build script creates the APK, notes template or copied notes, and
  checksums.
- GitHub release publishing is performed from the local release script, not from
  Trellis task automation.
