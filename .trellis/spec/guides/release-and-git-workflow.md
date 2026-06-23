# Release And Git Workflow

> **Purpose**: Define the project's command-level release flow, release notes
> coverage gates, and Git/GitHub boundaries.

---

## Scope

Use this guide when preparing a public release for the Android project or when
changing the local release scripts under `.release_local/release/`.

This guide is about the repository's release process, not Trellis task
bookkeeping.

## Non-Negotiable Rules

- Run the commands in this document from the repository root in PowerShell.
- If any command fails, stop. Do not continue by memory or by estimating the
  missing step.
- Never publish a GitHub release until the final commit ledger has been checked
  against the release notes.
- Never write release notes from the most recent commits only. The required
  range is always the previous semver tag through the final release commit.
- Do not treat `Major changes` as a curated highlights section. It is the commit
  ledger section and must contain every commit record in the final release
  range.
- Do not commit `.release_local/` or `release_artifacts/`.
- Release notes are the GitHub Release body only. Never put
  `RELEASE_NOTES_vX.Y.Z.md` in `release_artifacts/`, never upload it as a
  GitHub release asset, and never include it in `SHA256SUMS.txt`.
- GitHub releases can be immutable after publication. If a bad asset is
  published, do not assume it can be deleted or overwritten. Stop and report the
  exact immutable state; deleting/recreating a release requires explicit
  maintainer approval.
- Public notes must be handwritten. Commit subjects may seed the ledger, but the
  feature and bug-fix explanations must be written from the actual changes.

## Command-Level Release Flow

Replace `$Version` with the release version, for example `0.7.1`.

### 1. Start From A Clean Synced Branch

```powershell
$Version = "0.7.1"
$Tag = "v$Version"

git status --short --branch
git fetch origin --tags
git pull --ff-only origin dev
git status --short --branch
```

Hard gate:

- `git status --short --branch` must show `dev` and no dirty files.
- If there are dirty files, stop and classify them before release work.
- If release must start from another branch, replace `dev` consistently in every
  command below and write that branch name in the final summary.

### 2. Resolve The Previous Version Tag

```powershell
$PreviousTag = git describe --tags --abbrev=0 --match "v[0-9]*"
if (-not $PreviousTag) { throw "No previous semver tag found." }

$ExistingTag = git tag --list $Tag
if ($ExistingTag) { throw "Tag $Tag already exists." }

"Previous tag: $PreviousTag"
"New tag: $Tag"
```

Hard gate:

- Do not guess the previous tag.
- Do not use a release note, README badge, or `VERSION` file as the previous
  release marker.
- The previous tag must come from Git.

### 3. Generate The Pre-Release Commit Ledger

```powershell
$ReleaseWorkDir = ".release_local\release_notes\$Tag"
New-Item -ItemType Directory -Force -Path $ReleaseWorkDir | Out-Null

$PreLedger = Join-Path $ReleaseWorkDir "COMMIT_LEDGER_PRE_RELEASE.txt"
git log --reverse --oneline "$PreviousTag..HEAD" | Tee-Object $PreLedger
```

Hard gate:

- Read the full output.
- If a bug-fix commit is present, it must later appear in `Bug fixes`.
- If a user-visible feature commit is present, it must later appear in
  `Main new features`.
- Trellis journal/archive commits may remain only in `Major changes`; they do
  not need user-facing explanation unless they changed public behavior.

### 4. Inspect The Actual Changes Behind The Ledger

Run the summary first:

```powershell
git diff --stat "$PreviousTag..HEAD"
```

For every non-bookkeeping commit in `$PreLedger`, inspect the commit before
writing release notes:

```powershell
git show --stat --summary <commit-sha>
git show --name-only --format=fuller <commit-sha>
```

Hard gate:

- Do not write notes from commit subjects alone.
- If a commit touches behavior and the subject is vague, inspect the code diff:

```powershell
git show <commit-sha> -- <path-from-name-only-output>
```

### 5. Build Artifacts And Sync Version Files

```powershell
powershell -ExecutionPolicy Bypass -File .\.release_local\release\build_release.ps1 -Version $Version
```

Hard gate:

- The build must finish successfully.
- The output directory must be `release_artifacts\$Tag\`.
- The script may create or replace files under `release_artifacts/`; never stage
  them.
- `release_artifacts\$Tag\` must contain release binaries and
  `SHA256SUMS.txt` only. It must not contain `RELEASE_NOTES_$Tag.md`.

### 6. Commit Only Version Source Changes

```powershell
git status --short
git diff -- VERSION android\LXB-Ignition\app\build.gradle.kts
git diff --check

git add VERSION android\LXB-Ignition\app\build.gradle.kts
git commit -m "chore(release): cut $Tag"
```

Hard gate:

- The release commit must only include version/source changes required to cut
  the release.
- Do not include `.release_local/`.
- Do not include `release_artifacts/`.
- Do not include release notes in Git unless a maintainer explicitly changes
  this policy.

### 7. Generate The Final Commit Ledger

After the release commit exists, regenerate the ledger from the previous tag to
the final release commit:

```powershell
$FinalLedger = Join-Path $ReleaseWorkDir "COMMIT_LEDGER_FINAL.txt"
git log --reverse --oneline "$PreviousTag..HEAD" | Tee-Object $FinalLedger
```

Hard gate:

- This final ledger is the authoritative release range.
- It must include the release commit itself.
- `Major changes` in the public notes must contain every line from this file.
- If the final ledger differs from the pre-release ledger, use the final ledger.

### 8. Write Handwritten Release Notes Outside `release_artifacts/`

Create the notes file outside the output directory so rebuilding artifacts does
not delete it and so it cannot be uploaded as an asset:

```powershell
$Notes = Join-Path $ReleaseWorkDir "RELEASE_NOTES_$Tag.md"
New-Item -ItemType File -Force -Path $Notes | Out-Null
notepad $Notes
```

Use exactly this structure:

```markdown
# vX.Y.Z

Date: YYYY-MM-DD
Changes since: vA.B.C

## Major changes

- <paste every line from COMMIT_LEDGER_FINAL.txt, in order>

## Main new features

- <handwritten user-visible feature explanation>

## Bug fixes

- <handwritten bug-fix explanation, including whether fixed fully>

## Notes

- <upgrade notes, compatibility notes, known limitations>

## 中文说明

### 主要变更记录

- <用同样顺序粘贴 COMMIT_LEDGER_FINAL.txt 的每一行>

### 新功能

- <中文手写功能说明，不要逐字机翻>

### 修复

- <中文手写 bug 修复说明，说明是否完全修复>

### 说明

- <中文升级说明、兼容性说明、已知限制>
```

Hard gate:

- `Major changes` and `主要变更记录` are commit ledger sections, not marketing
  summaries.
- `$Notes` must live under `.release_local\release_notes\$Tag\` or another
  explicit local notes directory, not under `release_artifacts\`.
- `$Notes` is passed to GitHub with `--notes-file`; it must not be copied into
  `release_artifacts\`, uploaded as an asset, or included in `SHA256SUMS.txt`.
- Every line from `$FinalLedger` must appear in the English `Major changes`
  section.
- Every non-bookkeeping behavior commit must also be explained in either
  `Main new features`, `Bug fixes`, or `Notes`.
- If a route lookup bug fix exists in the ledger, it must be explicitly
  mentioned in `Bug fixes`.
- If logging, workflow, route, LLM, quick task, startup, parser, storage, or UI
  behavior changed, the relevant section must say so directly.

### Coverage Matrix Requirement

Create a coverage file before publishing:

```powershell
$Coverage = Join-Path $ReleaseWorkDir "COVERAGE_CHECK.md"
New-Item -ItemType File -Force -Path $Coverage | Out-Null
notepad $Coverage
```

Use this table and fill one row for every non-bookkeeping commit:

```markdown
| Commit | Type | Public impact | Covered in notes |
|--------|------|---------------|------------------|
| <sha subject> | feature/fix/docs/chore | <what changed> | Main new features / Bug fixes / Notes |
```

Hard gate:

- A non-bookkeeping commit with an empty `Covered in notes` cell blocks release.
- A fix commit covered outside `Bug fixes` needs an explicit reason in the
  matrix.
- Do not publish until this matrix is complete.

### Mechanical Notes Validation

Run these checks before tagging or publishing:

```powershell
$NotesText = Get-Content $Notes -Raw
$MissingLedger = Get-Content $FinalLedger | Where-Object {
  $line = $_.Trim()
  $line -and -not $NotesText.Contains($line)
}
if ($MissingLedger) {
  throw "Release notes missing commit ledger lines:`n$($MissingLedger -join "`n")"
}

foreach ($Pattern in @(
  "^# $Tag$",
  "^Changes since: $PreviousTag$",
  "^## Major changes$",
  "^## Main new features$",
  "^## Bug fixes$",
  "^## Notes$",
  "^## 中文说明$",
  "^### 主要变更记录$",
  "^### 新功能$",
  "^### 修复$",
  "^### 说明$"
)) {
  if (-not (Select-String -Path $Notes -Pattern $Pattern -Quiet)) {
    throw "Release notes missing required heading or value: $Pattern"
  }
}

if (Select-String -Path $Notes -Pattern "TODO|TBD|placeholder" -Quiet) {
  throw "Release notes still contain placeholder text."
}
```

Hard gate:

- These checks passing is necessary but not sufficient.
- The coverage matrix must also be complete.

### 9. Tag The Release

```powershell
git status --short --branch
git tag -a $Tag -m $Tag
```

Hard gate:

- The working tree must be clean before tagging.
- The tag must point at the release commit.

Verify:

```powershell
git log --oneline --decorate -1
git show --stat --summary $Tag
```

### 10. Push Branch And Tag

```powershell
git push origin dev --tags
```

Hard gate:

- Do not publish GitHub Release before the branch and tag are pushed.

### 11. Publish GitHub Release

```powershell
powershell -ExecutionPolicy Bypass -File .\.release_local\release\build_release.ps1 `
  -Version $Version `
  -PublishGitHub `
  -ReleaseNotesFile $Notes
```

Verify the public release and assets:

```powershell
$ReleaseJson = gh release view $Tag --repo wuwei-crg/AutoLXB --json tagName,name,url,isDraft,isPrerelease,assets | ConvertFrom-Json
$ReleaseJson

$ReleaseApi = gh api "repos/wuwei-crg/AutoLXB/releases/tags/$Tag" | ConvertFrom-Json
if ($ReleaseApi.immutable) {
  "Release is immutable; asset mistakes cannot be fixed by upload/delete."
}

$AssetNames = @($ReleaseJson.assets | ForEach-Object { $_.name })
if (-not ($AssetNames -contains "lxb-ignition-$Tag.apk")) {
  throw "Missing APK asset."
}
if (-not ($AssetNames -contains "SHA256SUMS.txt")) {
  throw "Missing SHA256SUMS.txt asset."
}
if ($AssetNames -contains "RELEASE_NOTES_$Tag.md") {
  throw "Release notes must be release body only, not an uploaded asset."
}
```

Hard gate:

- `isDraft` must be `false`.
- `isPrerelease` must be `false` unless intentionally publishing a prerelease.
- Assets must include the APK and `SHA256SUMS.txt`.
- Assets must not include `RELEASE_NOTES_$Tag.md` or any other release-notes
  markdown file.
- `SHA256SUMS.txt` must list the APK and other real binary artifacts only; it
  must not list release notes or itself.
- If the release is immutable and the asset list is wrong, stop immediately and
  report the mismatch. Do not retry `gh release upload --clobber` or
  `gh release delete-asset` as a fix path.

### 12. Merge Release State To `master`

For this repository's normal dev-to-master release flow:

```powershell
git checkout master
git pull --ff-only origin master
git merge --ff-only dev
git push origin master
git checkout dev
git status --short --branch
```

Hard gate:

- Use `--ff-only`. If fast-forward fails, stop and ask for branch policy
  guidance.
- End on `dev` unless the maintainer explicitly asks otherwise.
- Final status must be clean.

## Final Response Requirements

After a release, the final response must state:

- Previous tag used.
- Final commit range used for release notes.
- Release commit SHA.
- Tag name.
- GitHub release URL.
- Uploaded assets, explicitly confirming there is no release-notes asset.
- Whether `master` was fast-forwarded and pushed.
- Final branch and clean/dirty state.
- Any warnings or known limitations from the build.

If any of these are missing, the release wrap-up is incomplete.
