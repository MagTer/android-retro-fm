# Information layout: aligning android-retro-fm with the shared rule

**Status: SIGNED by the operator 2026-11-01. Written 2026-10-29 as a plan.** Origin: operator, 2026-10-29
— *"Vi vill inte tappa information, men det ska hanteras enligt gemensamma rutiner och inte
bloat'a sessionen. Repots readme i rooten är främst för människor, agents.md är
projektspecifika tillägg. Vissa saker kanske ska bli projekt-lokala skills."* The rule is
agent-config `docs/planning/2026-10-29-project-information-layout.md` (U1 there, unsigned) and
`shared/core.md` "Session state". **Runs only after that U1 is signed.**

## 1. What is here, read 2026-10-29 at `04381d9`

| Reading | Command | Value |
|---|---|---|
| Every-turn file | `wc -w CLAUDE.md` | **14 616 words** |
| by section | word count per `##`/`###` | Conventions & gotchas 4 028 · **Now-playing metadata 8 064** (of it "The station moved to a new CDN" 7 440, "retrofm.se: dead ends" 445) · Field logs 1 830 · Build 341 · Release 248 |
| Project skills | `ls .claude/skills` | none |
| Citations of `CLAUDE.md` | `git grep -n CLAUDE.md -- ':!CLAUDE.md'` | 11 in 8 files: 6 code comments in `core/`, 4 in README (pointing *people* at the agent file), 1 in REQUIREMENTS.md |
| Position file | `ls -a .agent-state.md` | none |

So 9 900 words — two thirds — load on every turn and serve two kinds of task: changing the
now-playing path, and reading field telemetry.

## 2. Where each part goes

| Today | Goes to |
|---|---|
| Build, Release, Conventions & gotchas | **`AGENTS.md`** (~4 600 words; over the proposal's 4 000-word hypothesis — the gotchas are traps that pass silently, and trimming them is not this plan) |
| Now-playing metadata (both `###`) | skill **`now-playing-metadata`**: the CDN move, StreamTitle, artwork, and the dead ends "so nobody re-runs them" |
| Field logs | skill **`field-logs`**: this app's queries and their traps; the seat's `telemetry-query` skill stays the tool |
| `CLAUDE.md` | `@AGENTS.md` |
| README's four "se `CLAUDE.md`" | what a person needs, said in README; a pointer only where the detail is agent-only |

## 3. Decisions

None beyond the shared rule. The one judgement — that the gotchas stay whole in AGENTS.md —
is stated above so it can be overruled.

## 4. Units

Gates: `./gradlew :core:testDebugUnitTest`, before and after. They see the comment edits in U2
only; each acceptance line is the real check. Docs only, so no release.

### U1 — `AGENTS.md`, the two skills, the ignore line
- **Files.** `AGENTS.md`, `CLAUDE.md`, `.claude/skills/{now-playing-metadata,field-logs}/SKILL.md` (plus the symlinks to it at
  other clients' project skill paths that agent-config's layout proposal U0 found necessary),
  `.gitignore` (`.agent-state.md`).
- **Acceptance.** Every `**bold lead**` and heading of the old file is found in AGENTS.md or a
  skill (a list diff, in the commit body); total words moved = words removed ± headers;
  `git check-ignore .agent-state.md`; a fresh session asked about a wrong track title loads
  `now-playing-metadata`.
- **Must not touch.** Code.

### U2 — citations follow
- **Files.** the 6 comments in `core/`, README (4), REQUIREMENTS.md (1).
- **Acceptance.** `git grep -n CLAUDE.md -- ':!CLAUDE.md'` finds no citation of a section;
  the gradle gate green.
