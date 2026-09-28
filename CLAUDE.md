# CLAUDE.md

Project context for Claude Code sessions working in this repo — **Group Claims Pipeline**, a portfolio project built to mirror Mutual of Omaha's Workplace Solutions claims/enrollment systems (see `PLAN.md` for the full architecture and phased build plan).

---

## Git / commit rules

- **Never add a "Co-Authored-By: Claude" line, or any AI/Claude attribution, to any commit message — ever, under any circumstance**, even if default tooling or a template suggests it. Commits in this repo are authored solely as the project owner. If asked to commit, strip any auto-attribution before committing.
- Create new commits rather than amending, unless explicitly told to amend.
- Never force-push, skip hooks, or bypass signing unless explicitly told to.
- Don't run `git add -A` / `git add .` — stage specific files by name.
- Commit only when explicitly asked to.

---

## Workflow

- **Plan Mode, explicit approval required** before any implementation — don't write code for a phase until the plan for that phase has been explicitly approved.
- **One phase per session** (see `PLAN.md` §10). Don't jump ahead into a later phase in the same session, even if it seems fast to knock out.
- **File a GitHub issue before implementing each phase**, and reference it in the PR/commits that close it.
- Once a phase's plan is approved, run it autonomously to completion — don't stop for confirmation on every sub-step within an approved phase.
- **`main` is protected — never commit directly to it, under any circumstance.** All work happens on a feature branch (one per phase, see `PLAN.md` §2.1) and lands only via a Pull Request that passes every required CI check (`PLAN.md` §9.1). If a command or tool would push straight to `main`, stop and open a branch/PR instead — don't work around branch protection even if it would be faster.
- **Claude Code opens the PR and stops there — it never merges it, even if CI is green.** Merging into `main` is a manual action the repo owner performs themself, every time. Don't use `gh pr merge` or any equivalent unless explicitly told to for that specific PR.
- Squash-merge PRs into `main` (owner does this manually) so history stays one clean commit per phase.
- `PLAN.md` is the source of truth for scope and progress. Corrections or scope changes get **appended as dated entries in its Session Log (§12)** — never silently rewritten in place elsewhere in the file.
- Check off a `PLAN.md` checkbox only when that deliverable is actually verified working (you ran it and saw it happen) — not merely when the code is written.

---

## Data & metrics integrity (non-negotiable)

- All claims, enrollment, and policy data in this project is **synthetic** — generated locally, never real Mutual of Omaha data or any real person's data. State this explicitly in the README and anywhere the project is described; never let it be implied otherwise.
- **Never fabricate metrics.** Every number that goes into docs, the README, or a resume bullet must come from an actual eval run, load test, or CI/test output — log it in `PLAN.md` §11 when it's produced. If a real number isn't available yet, say so rather than estimating one.
- **Adjudication (claim approve/deny) is rule-based and deterministic**, not LLM-driven — every decision must be traceable via `ruleTrace`. The RAG/LLM layer (`rag-assistant-service`) explains decisions and answers policy questions; it must never be the thing that actually makes an adjudication decision. Do not blur this line, including for a "quick fix" or a demo shortcut — this distinction is the difference between an interview-defensible project and a liability.

---

## AWS cost discipline (non-negotiable)

- **Zero tolerance for unexpected charges.** The budget alarm is a backstop, never the primary control — the primary control is not leaving billable resources running unattended.
- **Default state for any billable AWS resource (EC2 instance, or anything added later — bigger instance sizes, other compute, etc.) is stopped/torn down.** Running is the exception, justified by active work happening right now.
- Never leave the EC2 instance (or any other billable resource created for this project) running after a work session, demo, or verification pass ends. Stopping it is the last step of that session's work, every time — and say so explicitly when it's done.
- Before starting the instance for a task (deploy, verify, test), say so first and give a rough expected duration. After the task, confirm explicitly that it's been stopped.
- Before any live verification pass (e.g. Phase 8 Part 2), say the instance is about to start; once verification is done, stop it before ending that session's work — don't leave it up "in case there's something else to check."
- If a step genuinely needs the instance to stay up between sessions (waiting on something async), flag that explicitly and get the owner's confirmation before leaving it running — never assume it's fine.

---

## Architecture quick reference

- `enrollment-service` (Spring Boot) — owns `enrollments`, exposes coverage lookups.
- `claims-intake-service` (Spring Boot) — creates claims, publishes `claim.submitted`. Since Phase 8b it also owns **appeals** (`appeals` collection; supporting documents in S3 on EC2, a local volume in dev) and **admin accounts** (`admin_users`; sign-up gated by `ADMIN_SIGNUP_CODE`, bcrypt passwords, HS256 bearer token, `/api/admin/**` requires the ADMIN role). An admin upholds or overturns a DENIED claim's appeal and it publishes `claim.appeal-decided`. The appeal decision is a **person's**, recorded on the appeal (reviewer, time, note), and never edits the claim's engine-written `status`/`decisionReason`/`ruleTrace`; no LLM reads documents or decides appeals.
- `adjudication-service` (Spring Boot) — consumes `claim.submitted`, runs the rule engine, calls Enrollment Service, publishes `claim.adjudicated`.
- `notification-service` (Spring Boot) — consumes `claim.adjudicated` and `claim.appeal-decided`, logs simulated notifications.
- `rag-assistant-service` (Python/FastAPI/LangGraph) — guardrail → retrieve → generate → groundedness-check graph over `policy_documents` via MongoDB Atlas Vector Search; generation uses a hosted LLM API with a provider fallback chain, and no generated answer is returned unless it passes the groundedness gate (otherwise it abstains); explains decisions and answers policy questions only. Built in two sub-phases, 7a (service) and 7b (eval + frontend) — see `PLAN.md` §10 and the 2026-09-19 Session Log entry.
- Kafka (Redpanda): topics `claim.submitted`, `claim.adjudicated`, `claim.appeal-decided`. Payloads are plain JSON (see `PLAN.md` §4 for the noted Avro/schema-registry tradeoff — don't add that complexity unless explicitly asked).
- MongoDB Atlas: `enrollments`, `claims`, `policy_documents` (vector-indexed), `notifications_log`, `appeals` and `admin_users` (both owned by claims-intake-service). Services don't reach into each other's collections directly — cross-service reads go through that service's REST API or an event, never a shared DB read.
- Deployment: single AWS EC2 free-tier instance running Docker Compose, MongoDB on Atlas, attachments in S3. Don't add AWS services beyond what `PLAN.md` §8 specifies without checking in first — the free-tier budget-alarm discipline matters.

---

## Tech stack

Java 21 · Spring Boot 3.x · Maven · Spring Data MongoDB · Spring for Apache Kafka · Redpanda · MongoDB Atlas + Atlas Vector Search · React (Vite) · Python 3.11 · FastAPI · LangGraph · Docker / Docker Compose · AWS (EC2 free tier, S3, IAM) · GitHub Actions.

## Testing expectations

- Java: **JUnit 5** for general unit tests, **Spock** specifically for the Adjudication Service's rule-engine specs, **Mockito** for mocking collaborators, **Testcontainers** for real Kafka/Mongo integration tests (not mocked).
- Frontend: **Vitest** for component/unit tests, **Playwright** for E2E (submit → status update).
- RAG service: **pytest**, scored against the fixed-seed eval set in `data/eval/` — treat a dropping eval score as a regression, not noise.
- CI must go lint → unit → integration → build, green, before anything is considered done for a phase.

## Coding conventions

- Services stay independent: no service queries another's MongoDB collection directly. Cross-service reads go through REST; cross-service reactions go through Kafka events.
- Favor clarity and defensibility over cleverness — this codebase doubles as an interview artifact, so a reviewer (or Vaishali's team) should be able to read the rule engine and event flow without narration.
- When a design choice trades off simplicity for realism (e.g., JSON over Avro, single EC2 instance over a cluster), note the tradeoff in a comment or the README rather than silently picking one — it should be defensible as a deliberate scoping decision, not an oversight.

## Communication style

- Direct, no filler.
- For anything that will end up as a resume or portfolio claim: prefer interview-defensibility over inflated metrics. When in doubt, understate and offer the real number instead.