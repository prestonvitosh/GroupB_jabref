> [!IMPORTANT]
> This project does not accept fully AI-generated pull requests. AI tools may only be used for assistance. You must understand and take responsibility for every change you submit.
>
> Read and follow:
> • [AGENTS.md](./AGENTS.md)
> • [CONTRIBUTING.md](./CONTRIBUTING.md)

# Group B CI/CD setup log and runbook

## Status

Target: <https://github.com/prestonvitosh/GroupB_jabref> (public fork of JabRef).
Working branch: `codex/groupb-cicd`.
Source commit: `046d7063bdb166bee786fe7830eddfd4e823528b`.

The user reviewed the prepared configuration and explicitly approved committing and pushing it. The setup commit `3611cca` was pushed to `origin/codex/groupb-cicd`. GitHub recognized the workflow as active and started run `36436543763`; its metadata job passed. No release tag has been created. Validation results below distinguish actual checks from checks that still require GitHub runners.

## Step-by-step work log

### 1. Inspect the starting workspace

Commands: `pwd`, `ls -la`, `rg --files`, `git status --short`, `git remote -v`, `git log -1 --oneline`, `command -v gh`, and `java -version`.

The workspace contained an empty Git repository with an unborn `main` branch, no files, and no remotes. GitHub CLI was installed. Local Java was version 21, while this JabRef revision requires Java 25.

### 2. Inspect upstream JabRef

Opened <https://github.com/JabRef/jabref> and fetched its `main` branch with:

```sh
git fetch --depth=1 https://github.com/JabRef/jabref.git main
git checkout -b codex/jabref-cicd FETCH_HEAD
```

The first network check failed because the restricted environment could not resolve GitHub. Fetch succeeded with approved network access. The first checkout could not write `.git/index.lock`; retrying with approved filesystem access succeeded. No existing source files were overwritten.

Read `AGENTS.md`, `CHECKLIST.md`, the repository's GitHub Actions skill, the existing tests and release workflows, composite actions, Java toolchain configuration, packaging configuration, Gradle properties, and submodule configuration.

### 3. Clarify publication requirements

The user selected a public repository and deployment on GitHub, then requested detailed documentation of every step. Because JabRef is a desktop application, deployment means GitHub Releases containing installers, rather than GitHub Pages hosting the application.

Initially checked whether `g-falast14/jabref` existed. It did not. An attempted fork command used an incompatible CLI flag combination and exited before creating a repository. The user then paused work and supplied the existing Group B repository. No new GitHub repository was created.

### 4. Verify identity and existing repository access

Commands:

```sh
gh auth status
gh repo view prestonvitosh/GroupB_jabref --json nameWithOwner,defaultBranchRef,viewerPermission,isFork,parent,url
gh api repos/prestonvitosh/GroupB_jabref/contents/.github/workflows --jq '.[].name'
gh run list --repo prestonvitosh/GroupB_jabref --limit 8
```

Authenticated identity: `g-falast14`. Access: `WRITE`. Default branch: `main`. The repository is a public fork of `JabRef/jabref`. No Actions runs were returned.

The restricted authentication check initially reported an invalid token; checking again with network access confirmed working authentication. No credentials were copied into repository files.

Reading Actions policy returned HTTP 403. Reading main-branch protection returned HTTP 404. These responses do not prove Actions is disabled or that protection is absent; administrative settings remain unverified.

### 5. Base the work on Group B's current main

Commands:

```sh
git remote add origin https://github.com/prestonvitosh/GroupB_jabref.git
git remote add upstream https://github.com/JabRef/jabref.git
git fetch origin main
git checkout -b codex/groupb-cicd origin/main
```

The existing repository's main commit matched the upstream commit already fetched. No merge, rebase, force push, or remote branch modification occurred.

### 6. Review inherited automation and select the replacement

The inherited automation contains upstream issue and pull-request management, private build-server uploads, Maven publication, external API credentials, and signing configuration.

Moved all 51 existing workflow YAML files from `.github/workflows/` to `.github/upstream-workflows/`, preserving their bytes. This intentionally deactivates inherited automation in the proposed configuration. It also deactivates upstream specialized checks such as external-service tests, native server smoke tests, link checks, and publication workflows. This replacement is not a claim of parity with every upstream quality gate.

The replacement workflow handles Group B builds, ordinary tests and Gradle checks, platform installers, and GitHub Releases. Existing Java application code, Gradle build configuration, license, and submodule pointers remain unchanged.

### 7. Pin and verify action dependencies

Read `skills/developers/github-actions/SKILL.md`, which requires full commit SHA pins. Resolved these tags using `gh api repos/OWNER/REPO/commits/TAG --jq .sha`:

| Action | Tag | Verified commit |
| --- | --- | --- |
| actions/checkout | v7 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| actions/setup-java | v6 | `de7274f081f381c8f8158605e0321c36c376e2e6` |
| gradle/actions | v6 | `9c971963bec38e04b3d30dcc455b5382be2fdbfb` |
| actions/upload-artifact | v7 | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` |
| actions/download-artifact | v8 | `3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c` |

The workflow uses the checked-in Gradle 9.8.0 wrapper instead of a moving Gradle version. Java setup uses Amazon Corretto 25, matching the source's toolchain vendor.

### 8. Implement the pipeline

File: `.github/workflows/groupb-ci-cd.yml`.

1. Trigger on pushes to `main` and the setup branch, pull requests to `main`, manual runs, and `groupb-v*` tags.
2. Validate tag format and installer version bounds; tagged commits must already belong to `origin/main`.
3. Initialize recursive submodules with checkout credentials disabled after checkout.
4. Set up Java 25 and Gradle caching. Only `main` writes Gradle caches.
5. Install headless GUI dependencies and run `xvfb-run --auto-servernum ./gradlew --no-daemon --stacktrace --max-workers=2 build` on Linux.
6. Upload test and static-analysis reports even after failure, retaining them for 14 days.
7. After checks pass, build Linux x64 DEB/RPM, Windows x64 MSI, and macOS Apple Silicon DMG/PKG installers on native runners using the existing named packaging tasks.
8. Smoke-test each packaged launcher with `--help`, require every expected installer type, prefix filenames with platform identifiers, and upload artifacts for 14 days.
9. On a release tag in the Group B repository only, wait for every package job, download that run's artifacts, create SHA-256 checksums, and publish a GitHub Release.

Build jobs have read-only repository permissions. Only the final release job has `contents: write`. Pull-request builds do not publish releases. Older branch runs may be canceled; tag runs are not automatically canceled. Every job has a timeout and failures are not ignored.

### 9. Prepare local validation tools

Downloaded Corretto 25 for macOS ARM64 into `/private/tmp/groupb-cicd-tools`, without changing installed Java versions. Downloaded actionlint 1.7.12 and its published checksums using `gh release download`; verified the archive SHA-256 before extraction. Created a temporary Python virtual environment for yamllint 1.38.0.

Initialized all five pinned submodules:

```sh
git submodule update --init --recursive --depth=1
```

Started the required core check using temporary Java and Gradle caches:

```sh
JAVA_HOME=/private/tmp/groupb-cicd-tools/amazon-corretto-25.jdk/Contents/Home \
GRADLE_USER_HOME=/private/tmp/groupb-cicd-tools/gradle \
./gradlew --no-daemon --console=plain :jablib:check
```

The command's output is captured locally at `/private/tmp/groupb-cicd-tools/gradle-check.log`. Temporary paths are specific to this setup machine.

### 10. Validation results

- Actionlint 1.7.12: passed for the new workflow.
- All five required Git submodules: fetched at their tracked commits.
- Yamllint 1.38.0: passed for the new workflow.
- Markdownlint 0.22.1: passed for both new Markdown files. The first run flagged the mandatory policy header before the heading; added the same MD041 exception used by upstream `AGENTS.md` and reran successfully.
- Extracted the actual workflow's version-validation shell step and exercised 10 cases: ordinary branch, valid tag, maximum supported version, zero major, major/minor/patch overflow, leading zero, prerelease suffix, and shell metacharacters. All accepted/rejected as expected.
- Extracted the actual installer-collection step and tested fixture files for all three platforms; also verified missing installers cause failure. All passed.
- Compared each archived workflow to `git show HEAD:.github/workflows/NAME`: all 51 are byte-identical.
- `git diff --check`: passed.
- `:jablib:check`: passed in 5m 21s; 547 XML report files recorded 11,496 tests, 0 failures, 0 errors, and 33 skipped tests.
- Full Markdown lint over `docs/**/*.md` and root `*.md`: passed across 180 files.
- `npm ci && npm run textlint`: passed. The installed Node 20.17.0 is slightly below the textlint package's declared minimum 20.18.0 and emitted engine warnings; the command nevertheless completed successfully. Package audit reported 0 vulnerabilities.
- Added launcher smoke tests and reran actionlint and yamllint successfully. These validate workflow syntax, not execution of the native launchers.
- Final self-review restricted release publication to tag-push events, so manually dispatched validation runs cannot publish a release.
- Full-module `checkstyleMain checkstyleTest checkstyleJmh`: passed in 39s.
- Full-module `modernizer`: passed in 9s, using the repository's existing violation policy.
- `./gradlew --no-configuration-cache :rewriteDryRun`: exited successfully in 1m 4s and reported “Applying recipes would make no changes.” It also reported three missing upstream recipes and a parsing problem in unchanged `SpecialFieldAction.java`. Therefore this is not an unqualified clean OpenRewrite validation. No automatic rewrite was applied.
- `./gradlew javadoc`: passed in 19s, with documentation warnings from unchanged application source.
- All four additional Gradle commands ran sequentially with `--no-daemon --console=plain --max-workers=2`, separate log files under the temporary tools directory, and the same temporary Java/Gradle environment.
- First local `:jabgui:jpackageMacos-15` attempt: failed after 34s because Apple's ad-hoc `codesign` rejected `com.apple.FinderInfo` metadata on the generated app. Inspection also found file-provider metadata on that app in this Documents workspace. This was a real failed build; it is not counted as a passing package validation.
- Retried the same task with a temporary Gradle init script that redirects only the `jabgui` build directory to `/private/tmp/groupb-cicd-tools/jabgui-build`. This avoids the workspace's file-provider directory and changes no tracked Gradle configuration. Both attempts used `VERSION=100.0.0` and `OSXCERT=false`. The retry successfully built the macOS application image and the packaged launcher passed `--help` with exit code 0. The retry completed successfully in 2m 43s and produced both DMG and PKG installers. The packaged macOS launcher returned exit code 0 for `--help`. Linux and Windows installers still require GitHub runner validation.
- GitHub-hosted validation: started at <https://github.com/prestonvitosh/GroupB_jabref/actions/runs/36436543763>; the metadata job passed. Remaining job results are pending.
- GitHub Release publication: not yet run.

### 11. Complete the repository review gate

Reviewed `CHECKLIST.md` against the final scope. Java nullability, exception handling, Java idioms, localization, HTML response escaping, model/logic tests, and fetcher implementation requirements do not apply because no application source or tests were changed. No application changelog or feature requirement entry is needed for this internal CI setup. Developer documentation is this file.

Verification commands and their actual results are listed above. Code-format repair is not applicable unless the dry run finds changes caused by this work. Pull-request-template requirements do not apply at this stage because no pull request is being created. The human-review publication gate was satisfied by the user’s explicit approval to commit and push.

## Operating the pipeline

### Enable and verify CI

After review and publication, open <https://github.com/prestonvitosh/GroupB_jabref/actions>. If GitHub displays the fork workflow opt-in screen, a repository administrator must enable Actions. If repository policy blocks an action or a runner, an administrator must resolve that policy restriction.

Open the `Group B CI/CD` workflow and inspect every job. Download installers and `test-reports` from a successful run. Confirm the resulting application starts on each supported operating system before treating a build as a validated distribution.

An administrator can require `Build and test` and all three `Package (...)` checks in the main-branch ruleset. This setup has not changed branch rules, collaborator permissions, billing, or Actions administration settings.

### Create a release

Use a reviewed, tested commit already on `main`. Choose an unused tag with three integer version components; `groupb-v6.0.0` is an example, not a tag created by this setup.

```sh
git fetch origin main
git tag groupb-v6.0.0 origin/main
git push origin groupb-v6.0.0
```

The workflow rebuilds and checks the tagged commit before release publication. Major version must be 1–255, minor 0–255, and patch 0–65535, without leading zeroes, to satisfy installer version constraints. Non-tag builds use the upstream development placeholder version `100.0.0`. Branch builds and manual runs create downloadable Actions artifacts only; publication requires a tag-push event.

Published assets are at <https://github.com/prestonvitosh/GroupB_jabref/releases>. After downloading all assets into one directory, Linux users can run `sha256sum --check SHA256SUMS.txt`. On macOS use `shasum -a 256 --check SHA256SUMS.txt`.

Installers are unsigned. macOS notarization and Windows signing need separately configured certificates and were not claimed or configured here. The macOS artifact targets Apple Silicon; there is no Intel macOS or Linux ARM64 job in this configuration.

### Recover from a failed run

Read the first failing job and its logs. A failed check blocks packaging; a failed package blocks release publication. Correct the problem through a normal commit and rerun CI. Failed builds do not become releases automatically.

Do not move or overwrite a published tag. The release command deliberately refuses to replace an existing release. A partial upload or existing release requires inspection before retrying; choose a new version for corrected public releases.

### Sync with upstream later

Use explicit fetch and merge, following `AGENTS.md`. Inspect incoming `.github/workflows` changes during every sync so upstream automations are not accidentally reactivated. Review Java/Gradle changes, platform task names, action pins, and installer paths together.

### 12. Final handoff

The user requested an expedited finish while the local packaging retry was running. No additional broad checks were added. All source-level changes remain limited to workflow placement, the new workflow, and documentation. Requested human review and approval to commit/push the prepared changes, citing `AGENTS.md:76`. The user replied “Approve committing and pushing.” Publication is proceeding under that explicit approval; GitHub runner results and release delivery must still be verified after publication.

### 13. Default-branch publication gate

Automatic approval review rejected the attempt to push the setup to `main`, stating that approval to commit and push did not explicitly authorize mutation of the shared default branch. The rejected command did not run. Requested specific approval to push the reviewed setup to `main`; no workaround was used. The setup branch and its live Actions run are already published.

## Repository policy and final review

`AGENTS.md` says, “Never commit generated code without human review.” It also says not to automate submission of code changes. The user reviewed the prepared configuration and documentation and explicitly approved committing and pushing them. No upstream JabRef pull request is being submitted.

For background on the application's structure, see <https://deepwiki.com/JabRef/jabref>.

<!-- markdownlint-disable-file MD041 -->
