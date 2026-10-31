# Release notes

Every release has a notes file at `release-notes/vX.YY.md`, and the GitHub release is created from that file
(`gh release create vX.YY --notes-file release-notes/vX.YY.md …`), so the release page, Settings and the in-app
"What's new" dialog all show the same words. `ReleaseNotesTest` checks every file in the folder against this page,
and the app parses the same format (`update/ReleaseNotes.kt`).

## Format

```
One plain sentence saying what this release is about.

## New
- What you can do now, in everyday words
## Improved
- What got faster, clearer or easier
## Fixed
- What was wrong and now isn't
```

- **Summary:** one line, 140 characters or fewer, before the first heading.
- **Sections:** only `## New`, `## Improved` and `## Fixed`, each at most once; leave out the ones you don't need.
  The dialog colours them green, blue and amber.
- **Bullets:** `- ` only, at most 5 per section, 90 characters or fewer each, one line each.
- **Voice:** what the person using the app sees, not how it was built. "Sleep times follow the time zone", not
  "SleepViewModel takes DateTicker.zone".
- **Leave out:** PR or issue numbers, commit hashes, links, images, tables, code blocks and nested bullets. The release
  page already links the commits.

Older releases written as one paragraph still show, as plain text, without sections.

## What goes in them

The notes cover **everything that reached `main` since the previous release**, not just the last PR. Find it all first:

```bash
gh release view --json tagName,publishedAt                      # the previous release and when it went out
gh pr list --state merged --base main --search "merged:>=<publishedAt>" --json number,title,body --limit 100
git log "$(git describe --tags --abbrev=0)"..main --oneline    # also catches commits that never had a PR
```

Read every PR title and description (and the commits with no PR), then write what a person using the app would notice:

- Group the changes under New / Improved / Fixed. Merge related PRs into one bullet; drop anything the person can't see
  (refactors, tests, CI, docs, build tooling, dependency bumps) unless it changes behaviour.
- Check each claim against the code or the app before it goes in. Don't copy a PR title that overpromises.
- If a release has nothing user-visible, say so in the summary ("Behind-the-scenes fixes only") and add no sections.
- Read it once as a person who has never seen the repo. If a bullet needs the codebase to make sense, rewrite it.

## Releasing

1. Write `release-notes/vX.YY.md` as above and run `./gradlew :app:testDebugUnitTest --tests 'dev.baseline.update.ReleaseNotesTest'`.
2. **Show the owner the notes and wait.** Whoever is doing the release (an agent included) pastes the full text of
   `release-notes/vX.YY.md` into the chat, lists the PRs and commits it was built from, and asks for confirmation or
   changes. Apply the changes to the file, show it again, and only continue on a clear yes. Do not tag, build the
   release APK for publishing, or run `gh release create` before that yes, and a yes to an earlier draft doesn't cover a
   later one.
3. Follow the release steps in `CLAUDE.md`. The GitHub release is created from the file, never typed into the web form:
   `gh release create vX.YY Baseline-X.YY.apk --notes-file release-notes/vX.YY.md`.
4. **The release body and the file must match.** After publishing, check, and if the notes need a fix change the file
   first, then `gh release edit vX.YY --notes-file release-notes/vX.YY.md`:

   ```bash
   diff <(gh release view vX.YY --json body -q .body) release-notes/vX.YY.md && echo same
   ```
