# PLAN.md — Group Claims Pipeline

**Target:** portfolio project mirroring Mutual of Omaha's Workplace Solutions claims/enrollment systems, built to discuss with Vaishali (Senior SWE / aspiring TPM, Mutual of Omaha) and, through her, her engineering team.

**Ground rules for this whole project (carried over from prior portfolio projects):**
- All claims/plan/enrollment data is synthetic, generated locally (Faker/Python scripts). This gets stated explicitly in the README and in any resume bullet — never implied to be real Mutual of Omaha data.
- No fabricated metrics. Every number that ends up in a resume bullet or in front of Vaishali must come from an actual eval run, an actual load test, or actual CI output — logged in this file when it's produced.
- Adjudication (approve/deny) is **rule-based**, not LLM-based. The LLM/RAG layer explains decisions and answers policy questions — it does not make the decision. This is what keeps the project interview-defensible.
- One phase = one working session. Each phase ends with a GitHub issue filed for the next phase before implementation starts on it.
- Corrections to this plan get appended as dated entries in the Session Log (§12), not silently rewritten in place.

---

## 1. Architecture at a glance

Four Spring Boot microservices talk to each other only through Kafka events, backed by a shared MongoDB Atlas cluster, fronted by a React app, with a separate Python/LangGraph RAG service answering natural-language questions. Everything is containerized and deployed to a single AWS EC2 free-tier instance.

```
React App
   │  submits claim
   ▼
Claims Intake Service (Spring Boot) ──publishes──▶ Kafka topic: claim.submitted
                                                          │
                                                     consumes
                                                          ▼
                                          Adjudication Service (Spring Boot)
                                          ├─ checks coverage → Enrollment Service
                                          ├─ reads/writes → MongoDB (claims, plan rules)
                                          └─ publishes ──▶ Kafka topic: claim.adjudicated
                                                                    │
                                                               consumes
                                                                    ▼
                                                   Notification Service (Spring Boot)
                                                       └─ notifies member (simulated)

React App ──asks question──▶ Claims & Policy Assistant (Python, LangGraph)
                                   └─ vector search ──▶ MongoDB Atlas Vector Search
                                                          (policy documents, chunked + embedded)
```

---

## 2. Repo layout & environment

Single monorepo (simplest to demo and to point Vaishali at one link):

```
claims-pipeline/
├── services/
│   ├── enrollment-service/         (Spring Boot, Java)
│   ├── claims-intake-service/      (Spring Boot, Java)
│   ├── adjudication-service/       (Spring Boot, Java)
│   ├── notification-service/       (Spring Boot, Java)
│   └── rag-assistant-service/      (Python, FastAPI + LangGraph)
├── frontend/                       (React)
├── infra/
│   ├── docker-compose.yml
│   ├── docker-compose.prod.yml
│   └── aws/ (deploy scripts, security group notes)
├── data/
│   ├── synthetic/ (generator scripts for claims, enrollments, plan docs)
│   └── eval/ (fixed-seed eval sets for RAG + adjudication rules)
├── .github/workflows/ci.yml
├── README.md
└── PLAN.md
```

### 2.1 Git workflow & branch protection

- **`main` is protected — nothing is ever committed to it directly, including by you.** All work happens on a feature branch and lands via Pull Request. No exceptions, not even a one-line fix.
- **One branch per phase**, named after the phase (e.g. `phase-0-scaffold`, `phase-2-claims-intake-kafka`, `phase-7-rag-assistant`). If a phase is large enough to want smaller PRs (Phase 3 and Phase 7 are the likely candidates), branch further off the phase branch or just split into a couple of PRs against `main` for that phase — either is fine, but each PR still has to pass CI and be merged before the next one starts.
- **Every PR must:**
  - Link the GitHub issue it closes (`Closes #N`) — see §10, one issue per phase.
  - Pass all required CI checks (see §9.1) before it's mergeable.
  - Get squash-merged into `main`, so `main`'s history reads as one clean commit per phase — this also keeps the history readable if you ever walk Vaishali's team through it.
- **Branch protection settings to configure on GitHub** (Settings → Branches → Add rule for `main`), as part of Phase 0:
  - Require a pull request before merging.
  - Require status checks to pass before merging — select each CI job from §9.1 once the workflow exists.
  - Require branches to be up to date before merging.
  - **Include administrators** (i.e. don't let the "no direct commits to main" rule quietly exempt the repo owner — this is the setting people forget, and it's the one that actually enforces the rule for a solo repo).
  - Note on required approvals: GitHub won't let you approve your own PR, so for a solo repo set "required approvals" to 0 and rely on the passing-CI requirement as the actual gate, rather than getting stuck unable to merge your own work.
- **Claude Code's job stops at opening the PR — merging is always a manual step you do yourself**, once CI is green and you've looked over the diff. This isn't about the approvals setting above; it's a separate rule that Claude Code (or any automation) never runs `gh pr merge` or clicks merge on your behalf, even when nothing is blocking it.

**Dev environment: WSL2, not native Windows.** Develop inside Ubuntu on WSL2, with VS Code's "Remote - WSL" extension so VS Code stays the interface while the filesystem and tools live in Linux. Reasons: Docker Desktop on Windows already runs on a WSL2 backend, so this removes a translation layer rather than adding one; Testcontainers (§9) has known rough edges with Docker sockets on native Windows; Redpanda's `rpk` CLI and most Kafka/Docker tooling assume a Unix shell; and volume-mount performance is noticeably better when the repo lives natively inside the WSL2 filesystem (`~/projects/...`) rather than under `/mnt/c/...`. Setup: `wsl --install` (Ubuntu) from an admin PowerShell, enable WSL2 integration for that distro in Docker Desktop's settings, clone the repo inside the Ubuntu filesystem, open with `code .` from the WSL terminal. PowerShell/native Windows stays fine for anything outside this project.

**Toolchain to install/confirm before Phase 0:**
- Java 21 (LTS), Maven (simpler than Gradle for a first Spring Boot project)
- Spring Boot 3.x, Spring Web, Spring Data MongoDB, Spring for Apache Kafka, Spring Boot Test
- Node 20+, React (Vite, not CRA — CRA is deprecated)
- Docker + Docker Compose
- Python 3.11+, FastAPI, LangGraph, pymongo
- AWS CLI v2, an AWS free-tier account
- A MongoDB Atlas account (free M0 cluster)
- GitHub repo with Actions enabled

---

## 3. MongoDB Atlas setup

1. Create a free account at mongodb.com/cloud/atlas, create an **M0 (free tier)** cluster — pick the AWS region closest to wherever your EC2 instance will live, since they'll talk to each other.
2. Database Access → create a user with a generated password (store in `.env`, never commit it).
3. Network Access → for local dev, allow your current IP; for the deployed version, allow the EC2 instance's IP (or 0.0.0.0/0 only if you're comfortable with that tradeoff — note it honestly as a simplification in the README, a real system would use a VPC peering connection).
4. Create database `claims_platform` with collections:
   - `enrollments` — `{ employeeId, employer, planType, effectiveDate, status }`
   - `claims` — `{ claimId, employeeId, planType, amountRequested, status, submittedAt, adjudicatedAt, decisionReason, ruleTrace }`
   - `policy_documents` — `{ docId, planType, section, text, embedding }` (the `embedding` field is what Atlas Vector Search indexes)
   - `notifications_log` — `{ claimId, channel, sentAt, message }`
5. Atlas Search → create a **Vector Search index** on `policy_documents.embedding` (dimension must match your embedding model's output — 384 if using a small open-source sentence-transformer, 1536 if using an OpenAI-style embedding API). This is the piece that makes RAG retrieval a MongoDB feature instead of a bolted-on separate vector database — worth stating explicitly when you explain the project.

---

## 4. Kafka setup (via Redpanda)

Use **Redpanda** instead of raw Kafka + Zookeeper — it speaks the Kafka protocol so `spring-kafka` works against it unchanged, but it's a single Docker container instead of a multi-process cluster, which matters a lot given you're learning this from zero.

`docker-compose.yml` service:
```yaml
redpanda:
  image: redpandadata/redpanda:latest
  command:
    - redpanda start
    - --smp 1
    - --overprovisioned
    - --kafka-addr PLAINTEXT://0.0.0.0:9092
    - --advertise-kafka-addr PLAINTEXT://redpanda:9092
  ports:
    - "9092:9092"
    - "9644:9644"   # admin/metrics
```

Optional but worth adding: **Redpanda Console** (a web UI for browsing topics/messages) — makes it dramatically easier to see events flowing while you're debugging, and gives you something visual to show Vaishali.

Two topics, created either via the console UI or `rpk topic create claim.submitted claim.adjudicated`:
- `claim.submitted` — produced by Claims Intake, consumed by Adjudication
- `claim.adjudicated` — produced by Adjudication, consumed by Notification

Message schema: keep it JSON to start (not Avro — Avro adds a schema registry you don't need for a portfolio project; mention in README that a production system would likely use Avro + schema registry for compatibility guarantees, so you can speak to the tradeoff without having built it).

`claim.submitted` payload:
```json
{ "claimId": "uuid", "employeeId": "string", "planType": "disability|dental|vision|life", "amountRequested": 1200.00, "description": "string", "submittedAt": "iso8601" }
```

`claim.adjudicated` payload:
```json
{ "claimId": "uuid", "status": "approved|denied", "decisionReason": "string", "ruleTrace": ["rule names applied"], "adjudicatedAt": "iso8601" }
```

Spring side: `@KafkaListener` for consumers, `KafkaTemplate<String, String>` for producers, Jackson for JSON (de)serialization. Keep a `DeadLetterPublishingRecoverer` on each consumer from day one — it's a small amount of extra config and it's exactly the kind of "production support and troubleshooting" concern the JD calls out.

---

## 5. Microservices detail

### 5.1 Enrollment Service
- Owns: `enrollments` collection.
- Endpoints: `POST /enrollments` (seed synthetic enrollments), `GET /enrollments/{employeeId}` (used by Adjudication to check coverage).
- No Kafka involvement — it's called synchronously by Adjudication via a simple REST call, since "is this person covered" is a direct question-answer, not an event.

### 5.2 Claims Intake Service
- Owns: initial claim record creation in `claims` (status `SUBMITTED`).
- Endpoint: `POST /claims` — validates required fields, writes to Mongo, publishes to `claim.submitted`.
- This is the service the React "submit a claim" form talks to.

### 5.3 Adjudication Service
- Consumes `claim.submitted`.
- Rule engine (plain Java, no ML): checks enrollment status via Enrollment Service, checks `amountRequested` against a per-plan-type limit table (seed this as static config or a `plan_rules` collection), checks for obvious duplicate submissions.
- Writes decision + `ruleTrace` (which rules fired) back to the `claims` document, publishes to `claim.adjudicated`.
- This is where your **real precision/recall numbers** come from later: seed a labeled synthetic dataset (some claims that should clearly pass, some that should clearly fail, some edge cases), run it through the rule engine, measure against the labels.

### 5.4 Notification Service
- Consumes `claim.adjudicated`.
- Writes a row to `notifications_log` and logs a simulated notification (no real email/SMS needed — state this plainly).

### 5.5 Claims & Policy Assistant (RAG service)
- Separate Python/FastAPI service — deliberately polyglot, and worth being ready to defend that choice: the transactional services need Java/Spring for the role you're targeting, but the AI layer plays to your actual LangGraph/RAG experience, and a Java+Python split across services is a normal enterprise pattern, not a shortcut.
- Endpoint: `POST /assistant/ask { question, claimId? }`.
- See §6 for the internals.

---

## 6. RAG / LLM architecture

**Ingestion (one-time / re-runnable script, `data/synthetic/generate_policy_docs.py`):**
1. Write a handful of synthetic group plan summary documents (disability, dental, vision, life) — coverage limits, exclusions, waiting periods. Clearly synthetic, clearly labeled as such.
2. Chunk each document (by section, ~300-500 tokens per chunk).
3. Generate embeddings for each chunk (use a free/local sentence-transformers model like `all-MiniLM-L6-v2` to avoid API costs and rate limits — note the tradeoff: lower quality than a hosted embedding API, but free and fast to iterate on).
4. Write each chunk + embedding into `policy_documents`.

**Query-time flow (LangGraph graph, mirrors the fallback-chain pattern from the ERP project):**
1. **Retrieve node** — embed the incoming question, run MongoDB Atlas Vector Search (`$vectorSearch` aggregation stage) to pull the top-k relevant chunks.
2. **Generate node** — pass the retrieved chunks + question to an LLM with a provider fallback chain (e.g., Groq → Gemini → a deterministic "I don't have enough information" stub if both fail) — same resilience pattern you already have real experience building and can speak to concretely.
3. **Guardrail node** — if the question is asking the assistant to *make* an adjudication decision rather than explain one, respond that it doesn't make decisions, only explains them, and point back to the rule engine's `ruleTrace`. This is a small but real design decision worth mentioning — it's the thing that keeps "RAG/LLM integration" from becoming "the AI approves claims."
4. Return the answer + which source chunks were used (cite your sources, same as this response format).

**Eval (before you claim any number):** build a small fixed-seed set of question/expected-answer pairs (20-30 is enough), score retrieval accuracy (right chunk retrieved) and answer correctness by hand or with a simple rubric. Report it exactly like the ERP project's "25/50 on a fixed-seed sample" — a real, scoped, honestly-labeled number beats an inflated one.

---

## 7. Docker & local development

- One `Dockerfile` per Spring Boot service — multi-stage build (Maven build stage → slim JRE runtime stage, e.g. `eclipse-temurin:21-jre-alpine`).
- One `Dockerfile` for the RAG service (`python:3.11-slim` base).
- One `Dockerfile` for the React app (build stage → served via nginx).
- `docker-compose.yml` brings up: redpanda, redpanda-console, all four Java services, the RAG service, the React app. MongoDB stays external (Atlas), referenced by connection string in `.env`.
- `docker-compose.override.yml` (gitignored) for local secrets/ports if needed; commit a `.env.example`.

---

## 8. AWS deployment (free tier)

1. **IAM**: create a dedicated IAM user (not your root account) with least-privilege access — EC2 + S3 only. This is a small detail that reads well in an interview ("I didn't just use root").
2. **EC2**: launch a `t3.micro` (free tier eligible, 750 hrs/month for 12 months) running Amazon Linux or Ubuntu. Install Docker + Docker Compose on it.
3. **Security Group**: open only the ports you need — 80/443 for the React app (or put it behind a simple nginx reverse proxy so only one port is public), and nothing else exposed to the internet; internal service-to-service traffic stays on the Docker network.
4. **S3**: create one bucket (free up to 5GB) for claim attachment uploads. Claims Intake Service gets IAM permission to `PutObject`/`GetObject` on that bucket only.
5. **Deploy mechanism**: simplest defensible path — `git pull` on the EC2 box, `docker compose -f docker-compose.prod.yml up -d --build`. A more polished version (optional, later phase): GitHub Actions builds images, pushes to ECR (free tier: 500MB/month), and SSHes into EC2 to pull + restart. Only build the ECR/Actions version if there's time left after the core system works — don't let deployment tooling eat the time budget for the actual services.
6. **Budget alarm**: set a $1 AWS Budget alert immediately after account creation, before deploying anything. Free tier is generous but it's easy to trip if you're not paying attention (e.g., a second EC2 instance left running).
7. **CloudWatch** (optional, free tier: basic monitoring is free): point container logs at CloudWatch Logs if you want "monitoring" as a talking point — otherwise `docker compose logs` is fine for a portfolio project and it's fine to say so.

---

## 9. Testing strategy (aligned to what the JD names)

**Java services:**
- **JUnit 5** for standard unit tests (rule engine logic, service layer).
- **Spock** (Groovy) for at least the Adjudication Service's rule-engine specs — the JD explicitly lists JUnit/Spock, and Spock's given-when-then style is genuinely a good fit for "given this claim and this plan, when adjudicated, then this decision" — worth actually using it rather than just JUnit everywhere, so you have something concrete to say if asked about it.
- **Mockito** for mocking the Enrollment Service REST client and Kafka producers in unit tests.
- **Testcontainers** for integration tests — spin up a real Redpanda + a real (or Atlas-hosted test DB) Mongo container in CI so your Kafka producer/consumer and Mongo repository code get tested against the real thing, not mocks. This is the single most "senior engineer" testing decision available here — worth prioritizing over more unit tests.
- **Spring Boot Test / MockMvc** for REST endpoint tests.

**React frontend:**
- **Vitest** for component/unit tests.
- **Playwright** for E2E (submit a claim → see it move through statuses) — you already have real Playwright experience from Expertiza 2.0, so this is a place you can move fast and it's a legitimate resume line, not new-tech risk.

**RAG service:**
- **pytest** for the FastAPI endpoints and the LangGraph node logic.
- The fixed-seed eval set from §6 doubles as a regression test — if a prompt change tanks the eval score, CI (or at least a pre-merge checklist) should catch it.

**CI (GitHub Actions):** lint → unit tests (Java + Python + JS) → Testcontainers integration tests → Docker build for all images → (optional later) deploy step. Mirrors the ERP project's CI pattern; reuse that experience directly. See §9.1 for the actual workflow structure.

### 9.1 CI/CD pipeline (GitHub Actions)

`.github/workflows/ci.yml` — triggers on every `pull_request` targeting `main`, and again on `push` to `main` after a merge:

- **`lint`** — Checkstyle or Spotless for each Java service, ESLint for React, Ruff for the Python service.
- **`test-java`** — `mvn test` per Spring Boot service (matrix over the four service directories), runs JUnit + Spock specs.
- **`test-integration-java`** — Testcontainers-based tests against real Redpanda + Mongo containers (GitHub-hosted runners support Docker-in-Docker out of the box, no extra setup needed).
- **`test-python`** — `pytest` for `rag-assistant-service`, including the fixed-seed eval set.
- **`test-frontend`** — Vitest unit tests for the React app.
- **`test-e2e`** — Playwright E2E; keep this only on PRs targeting `main` (not every push to a feature branch) since it's the slowest job.
- **`build-docker`** — builds (not pushes) every service's Dockerfile, to catch broken builds before merge.
- **`deploy`** — only runs on `push` to `main` (i.e. after a PR has already merged), never on a PR itself. From Phase 8 onward: builds + pushes images to ECR, then redeploys to the EC2 instance. Keep this job absent/disabled until Phase 8 so earlier PRs aren't blocked on deployment infrastructure that doesn't exist yet.

Each of `lint`, `test-java`, `test-integration-java`, `test-python`, `test-frontend`, and `build-docker` becomes a **required status check** in the `main` branch protection rule from §2.1 — `test-e2e` can be required too once Phase 9 lands it, but don't block earlier phases on an E2E suite that doesn't exist yet. Add a CI status badge to the README once the workflow is green.

---

## 10. Development phases (one session each)

Check off a phase only when its deliverable actually works end-to-end, not when the code is written — e.g. Phase 2 isn't done until you've actually watched a message land in Redpanda Console, not just written the producer code.

- [x] **Phase 0** — Repo scaffold, docker-compose skeleton (empty services), CI skeleton that at least runs a no-op, branch protection live
  - [x] GitHub issue filed for Phase 0
  - [x] Repo created, folder structure in place
  - [x] `docker-compose.yml` builds and starts (even if services are empty stubs)
  - [x] `main` branch protection configured per §2.1 (PR required, status checks required, administrators included)
  - [x] CI workflow (§9.1) created and runs green via a PR from a `phase-0-scaffold` branch — confirms the PR-only flow actually works before any real code depends on it
- [x] **Phase 1** — MongoDB Atlas cluster live, collections created, Enrollment Service CRUD + seeded synthetic data
  - [x] GitHub issue filed for Phase 1
  - [x] Atlas M0 cluster created, network access + DB user configured
  - [x] Collections created (`enrollments`, `claims`, `policy_documents`, `notifications_log`)
  - [x] Enrollment Service CRUD endpoints working against Atlas
  - [x] Synthetic enrollment data seeded
- [x] **Phase 2** — Claims Intake Service + Redpanda wired up, `claim.submitted` flowing, verified in Redpanda Console
  - [x] GitHub issue filed for Phase 2
  - [x] Redpanda + Redpanda Console running in docker-compose
  - [x] `claim.submitted` topic created
  - [x] Claims Intake Service publishes on claim submission, confirmed via `rpk topic consume` (Redpanda Console itself confirmed reachable; the literal visual check is left for the repo owner at `localhost:8080`, since Claude Code can't view a browser UI)
- [x] **Phase 3** — Adjudication Service rule engine + `claim.adjudicated` published; first real precision/recall numbers logged
  - [x] GitHub issue filed for Phase 3
  - [x] Rule engine implemented (coverage check, plan-limit check, duplicate check)
  - [x] Consumes `claim.submitted`, publishes `claim.adjudicated`
  - [x] Labeled synthetic claim set built and run through the engine
  - [x] Precision/recall logged in §11 — real numbers, not estimates
- [x] **Phase 4** — Notification Service consuming and logging
  - [x] GitHub issue filed for Phase 4
  - [x] Consumes `claim.adjudicated`, writes to `notifications_log`
- [x] **Phase 5** — React frontend: submit form + live claim-status view
  - [x] GitHub issue filed for Phase 5
  - [x] Claim submission form wired to Claims Intake Service
  - [x] Status view reflects the claim moving through submitted → adjudicated
- [x] **Phase 6** — Synthetic policy docs generated, chunked, embedded, Atlas Vector Search index live
  - [x] GitHub issue filed for Phase 6
  - [x] Synthetic plan documents written (clearly labeled synthetic)
  - [x] Chunking + embedding script run, `policy_documents` populated
  - [x] Atlas Vector Search index created and queryable
- [ ] **Phase 7** — RAG Assistant service (LangGraph, fallback chain, guardrail node) + `/assistant/ask` + frontend Q&A widget; eval run, real numbers logged
  - [ ] GitHub issue filed for Phase 7
  - [ ] Retrieve → generate → guardrail graph implemented
  - [ ] LLM provider fallback chain working (tested by forcing a failure)
  - [ ] Frontend Q&A widget wired up
  - [ ] Fixed-seed eval set built and scored, logged in §11
- [ ] **Phase 8** — AWS deployment: EC2 + S3 + security groups + budget alarm; system reachable at a public URL
  - [ ] GitHub issue filed for Phase 8
  - [ ] IAM user created (not root), least-privilege policy attached
  - [ ] EC2 instance launched, Docker installed
  - [ ] Security group locked down to only needed ports
  - [ ] S3 bucket created, attachment upload working
  - [ ] Budget alarm set
  - [ ] Full system reachable at a public URL
- [ ] **Phase 9** — Testing hardening: Testcontainers integration tests, Spock specs, Playwright E2E, CI green end-to-end
  - [ ] GitHub issue filed for Phase 9
  - [ ] Testcontainers integration tests for Kafka + Mongo
  - [ ] Spock specs for Adjudication Service rule engine
  - [ ] Playwright E2E covering submit → status update
  - [ ] Full CI pipeline green (lint → unit → integration → build)
- [ ] **Phase 10** — README with honest synthetic-data disclosure + architecture diagram, real metrics writeup, resume bullets drafted, demo script for the Vaishali meeting
  - [ ] GitHub issue filed for Phase 10
  - [ ] README written, synthetic data disclosed explicitly
  - [ ] Architecture diagram included
  - [ ] §11 metrics checklist fully filled with real numbers
  - [ ] Resume bullets drafted from real metrics only
  - [ ] Demo script/talking points prepared for the Vaishali meeting

---

## 11. Metrics checklist (only real, measured numbers go here — update as each phase produces them)

- [x] Adjudication rule engine: precision / recall against labeled synthetic claim set (n = 33) —
      **precision = 1.000, recall = 1.000** (TP=15, FP=0, TN=18, FN=0), from
      `RuleEnginePrecisionRecallTest` in adjudication-service, run 2026-09-13. Dataset is
      hand-labeled synthetic claims run directly against `RuleEngine` (not through the live
      HTTP/Kafka pipeline) — this measures whether the implementation matches its own rule
      definitions, which is the correct thing to measure for a deterministic rule-based engine.
      A perfect score here reflects that the rules are simple and exhaustively covered by the
      dataset, not that the engine has been tested against real-world noisy data.
- [ ] RAG retrieval accuracy on fixed-seed eval set (n = ?)
- [ ] RAG answer correctness (hand-scored or rubric-scored) on same eval set
- [ ] Test coverage % (Java services combined, and RAG service separately)
- [ ] CI pipeline runtime (before/after any optimization, if you do one)
- [ ] End-to-end event latency: time from `claim.submitted` publish to `claim.adjudicated` publish (measured, not estimated)

---

## 12. Session Log (append-only — corrections and scope changes go here, dated, never silently rewritten above)

- **2026-09-15** — Correction to the Phase 6 entry immediately below, made before merge in
  response to review feedback on PR #15. The original entry is left as-is (per this file's own
  rule against silently rewriting logged results); this entry records what changed.

  The "known gap" noted below (chunks at ~120-245 tokens, short of §6's 300-500 token target)
  was addressed by genuinely expanding each policy doc's weakest sections — more specific limit
  breakdowns, additional concrete exclusion examples, and additional defined terms — rather than
  padding with filler. All four docs in `data/synthetic/policy_docs/` were rewritten, and
  `generate_policy_docs.py` was re-run to re-upsert all 16 chunks in place (same `docId`s, no
  new documents created). **Result: all 16 chunks now land between 306 and 441 tokens**
  (word-count-based estimate), inside the §6 target range. Still 16 chunks total, 4 per plan
  type, 384-dim embeddings — confirmed via a direct Atlas count.

  Both `$vectorSearch` verification queries were re-run against the re-embedded chunks (via
  `verify_vector_search.py`, now generalized to accept a question argument instead of hardcoding
  one):
  - "What is the maximum benefit for a dental claim?" — still correctly ranks
    `dental_coverage-limits` first (score 0.854, effectively unchanged from the original run).
  - "Is suicide excluded from the life insurance benefit?" — **the ranking did not flip**:
    `life_coverage-limits` (0.798) still ranks fractionally above `life_exclusions` (0.785),
    though the score gap narrowed from 0.023 to 0.014. Reported honestly rather than claimed as
    fixed — richer content measurably tightened the gap but did not resolve the underlying
    limitation, which is a genuine property of a small, general-purpose embedding model applied
    to short, closely-related insurance chunks, not a content-thinness problem after all. Both
    results remain correctly plan-scoped (all top-3 hits for each query belong to the right
    `planType`), which is the property that actually matters for Phase 7's retrieval step.
- **2026-09-15** — Phase 6 implemented on branch `phase-6-policy-docs-vector-search` (issue #14).

  **Synthetic policy docs**: `data/synthetic/policy_docs/{disability,dental,vision,life}.md`,
  each opening with an explicit "SYNTHETIC DATA" disclaimer, then `##` sections (Coverage
  Limits, Exclusions, Waiting Periods, Definitions). Coverage-limit figures were deliberately
  set to match `adjudication-service`'s actual `adjudication.plan-limits.*` config
  (disability $5,000 / dental $2,000 / vision $500 / life $10,000) so the RAG assistant's
  answers in Phase 7 will agree with what the rule engine actually enforces, rather than
  describing a different, undocumented set of numbers.

  **Chunking + embedding**: `data/synthetic/generate_policy_docs.py` splits each doc on `##`
  headers, embeds each chunk's body text with `sentence-transformers`' `all-MiniLM-L6-v2`
  (384-dim, matching §3/§6's free/local-model choice), and upserts into `policy_documents` on
  a stable `docId` (`{planType}_{section-slug}`) for idempotent re-runs. Run via WSL's Python
  3.12 (`data/synthetic/.venv`), not the Windows host's Python 3.14 — `sentence-transformers`/
  `torch` wheels aren't yet stable there. 16 chunks produced (4 per plan type), confirmed via
  a direct Atlas count and a spot-checked document (`dental_coverage-limits`, 384-length
  embedding array).

  **Known gap, stated honestly rather than fudged**: chunks landed at ~120-245 tokens
  (word-count-based estimate) per section, below §6's ~300-500 token target — the synthetic
  docs were authored for clear, defensible per-topic content rather than padded to hit a word
  count. Not re-padded further to chase the number artificially.

  **Vector index**: `data/synthetic/create_vector_index.py` creates
  `policy_documents_vector_index` (type `vectorSearch`, 384-dim `embedding` field, cosine
  similarity, `planType` as a filter field) via `pymongo`'s `create_search_index`, idempotent
  (skips if already present), and polls `list_search_indexes()` until `queryable: true` rather
  than assuming success once the create call returns — Atlas builds these asynchronously (took
  about 30s in practice: 5 poll cycles at 5s each before reporting queryable).

  **Verified live, not just "created"**: ran a real `$vectorSearch` query (`data/synthetic/
  verify_vector_search.py`) for "What is the maximum benefit for a dental claim?" — top result
  was `dental_coverage-limits` (score 0.854), followed by `dental_exclusions` (0.794) and
  `dental_waiting-periods` (0.772) — all four dental chunks would have been correctly
  plan-scoped even before ranking. A second, ad-hoc query for "Is suicide excluded from the
  life insurance benefit?" returned `life_coverage-limits` (0.799) ranked above
  `life_exclusions` (0.776) — correctly plan-scoped (all top-3 were `life_*`) but not perfectly
  ranked by topical specificity for this query, a real and unsurprising limitation of a small
  MiniLM model over short, closely-related chunks, logged honestly rather than only reporting
  the cleaner first example.

  This seeded `policy_documents` data and the vector index are the **permanent** dataset Phase
  7's RAG assistant will read from — unlike the ad-hoc claims/enrollments created during
  Phases 3-5's manual verification, nothing here was deleted afterward.
- **2026-09-15** — Phase 5 implemented on branch `phase-5-frontend-claim-form` (issue #11).

  **Backend gap found and closed**: `claims-intake-service` had no way to read a claim's
  current status. Added `ClaimRepository.findByClaimId`, `GET /claims/{claimId}` on
  `ClaimController` (200 with the full `Claim` document, including `status`/`decisionReason`/
  `ruleTrace`/`adjudicatedAt` once adjudication-service has written them back — reading from
  claims-intake's own `claims` collection, the same sanctioned same-collection write-back
  already established in Phase 3/§5.3, not a new cross-service read), and a `WebConfig`
  (`WebMvcConfigurer`) enabling CORS on `/claims/**` for an origin read from a new
  `CORS_ALLOWED_ORIGIN` env var (`cors.allowed-origin=${CORS_ALLOWED_ORIGIN:http://localhost:3000}`
  in `application.properties`, following the same env-var-with-default pattern as
  `MONGODB_URI`/`KAFKA_BROKERS`/`ENROLLMENT_SERVICE_URL`). Wired as an actual env var (not a
  hardcoded value) specifically so Phase 8's AWS deploy only needs to set
  `CORS_ALLOWED_ORIGIN` to the real deployed frontend origin — no code change required then.
  This is a real forward-pointer to Phase 8, not yet done: the current
  `infra/docker-compose.yml` value (`http://localhost:3000`) will need to change at deploy time
  or claim submission will silently fail from the deployed site.

  **Frontend**: new `frontend/src/api.js` (thin fetch wrapper, base URL from
  `import.meta.env.VITE_CLAIMS_API_URL`, default `http://localhost:8082`), `ClaimForm.jsx`
  (controlled form matching `ClaimRequest`'s fields/constraints), `ClaimStatus.jsx` (polls
  `GET /claims/{claimId}` every 2s, capped at 30 attempts/60s). Scope decision, made after
  review: `ClaimStatus` does not go silent if the cap is hit while still `SUBMITTED` — it
  switches to a visible "still processing, taking longer than expected" state with a manual
  retry button, and renders a distinct error state if a poll request itself fails (network/5xx).
  Reasoning: a silent stop would look like the app froze in a live demo rather than surfacing
  that the backend (adjudication-service down, Kafka backed up) is the actual problem.
  `App.jsx` now holds the current-claim state and switches between the form and the status view.
  `frontend/Dockerfile` takes `ARG VITE_CLAIMS_API_URL` (Vite inlines `VITE_*` at build time,
  so this has to be a build arg, not a runtime container env var); `infra/docker-compose.yml`
  passes it as `http://localhost:8082` (correct because the browser, running on the host, calls
  claims-intake-service via its host-published port, not the internal Docker network name) and
  sets `CORS_ALLOWED_ORIGIN: http://localhost:3000` on `claims-intake-service`. Added a
  `frontend/.dockerignore` (`node_modules`, `dist`) and root `.gitignore` entries for
  `node_modules/`/`dist/` — neither existed before and a stray `node_modules` briefly broke the
  frontend's Docker build context during verification.

  Testing: `ClaimControllerTest` gained found/not-found cases for the new GET endpoint (Spring
  MockMvc, matching the existing pattern) — 7/7 tests pass, run via a throwaway
  `maven:3.9-eclipse-temurin-21` container since Maven isn't installed on the host. Frontend:
  `ClaimForm.test.jsx`, `ClaimStatus.test.jsx` (Vitest fake timers covering the terminal-status
  path, the attempt-cap "still processing" path with working retry, and the request-failure
  error path), and an updated `App.test.jsx` — 8/8 tests pass, ESLint clean, run via a
  throwaway `node:20-alpine` container (npm's install scripts don't work over this machine's
  UNC-path repo location natively). No Playwright E2E this phase — that's Phase 9 per §9/§10.

  Verified live against the real running stack, not just unit-tested: rebuilt and restarted
  `claims-intake-service` and `frontend` in `infra/docker-compose.yml` (the rest of the stack
  had to be brought back up too — it was found stopped at the start of this session). Seeded a
  real ACTIVE dental enrollment (`EMP-PHASE5-1`), submitted a real claim via `POST /claims` with
  an `Origin: http://localhost:3000` header and confirmed the response carried
  `Access-Control-Allow-Origin: http://localhost:3000`, then polled the new
  `GET /claims/{claimId}` and confirmed it reflected `SUBMITTED` → `APPROVED` with a populated
  `ruleTrace` — the same transition `ClaimStatus.jsx` renders. Confirmed `http://localhost:3000`
  serves the built app (200, contains the page heading). Browser-level visual/interactive
  confirmation of the form and status view was not performed in this session (no browser-driving
  tool available) — left to the repo owner, same precedent noted in Phase 0's session log entry.
  All test claim/enrollment/notification-log data created during manual verification
  (`EMP-PHASE5-1`) was deleted from Atlas afterward.
- **2026-09-13** — Phase 4 implemented on branch `phase-4-notification-service` (issue #9).
  Added `spring-boot-starter-data-mongodb`, `spring-kafka`, and Testcontainers
  (`junit-jupiter`, `kafka`, `mongodb`) to `notification-service`. New classes:
  `NotificationLog`/`NotificationLogRepository` (exact §3 schema: `claimId, channel,
  sentAt, message`), `ClaimAdjudicatedEvent` (mirrors adjudication-service's producer
  shape), `NotificationConsumer` (`@KafkaListener` on `claim.adjudicated`, writes a
  `notifications_log` document and logs one INFO line simulating the send — no real
  email/SMS, per §5.4), and `KafkaConsumerConfig` (second Kafka consumer in the repo,
  copied unchanged from adjudication-service's pattern: `ErrorHandlingDeserializer` +
  `JsonDeserializer` with `use.type.headers=false`/`value.default.type` since producers
  never add a `__TypeId__` header, `DeadLetterPublishingRecoverer` → `claim.adjudicated.DLT`).

  Scope decisions: no REST endpoint this phase (consume-only, per §5.4); `channel`
  hardcoded to `"EMAIL"` (only one simulated channel, no invented selection logic);
  notification message built only from the fields `claim.adjudicated` actually carries
  (`claimId`, `status`, `decisionReason`) — no extra REST call to enrich it with
  `planType`/`employeeId`. **Known limitation, stated deliberately**: no deduplication on
  notification writes — Kafka's at-least-once delivery could occasionally produce a
  duplicate `notifications_log` entry, an accepted simplification for a simulated log.

  Testing: `NotificationConsumerTest` (JUnit 5 + Mockito, approved and denied cases) and
  one Testcontainers integration test (`NotificationIntegrationTest`, real Kafka + Mongo)
  proving the consume→write pipeline — all passed, checkstyle clean.

  Verified live against the real running stack: rebuilt and restarted
  `notification-service` in `infra/docker-compose.yml` (added `KAFKA_BROKERS`,
  `depends_on: redpanda`). Seeded a real enrollment, submitted one claim that got
  approved and one that got denied (no enrollment), confirmed via Mongo that both
  produced correct `notifications_log` documents (`channel: "EMAIL"`, populated
  `message`, recent `sentAt`) and confirmed the matching simulated-send INFO log lines.
  Published a deliberately malformed message directly to `claim.adjudicated` and
  confirmed it landed on `claim.adjudicated.DLT`, with the listener staying alive
  (health check + normal request processing continued afterward). All test
  claims/notifications/enrollments created during manual verification were deleted from
  Atlas afterward.
- **2026-09-13** — Phase 3 implemented on branch `phase-3-adjudication-rule-engine` (issue #7).
  Added `spring-boot-starter-data-mongodb`, `spring-kafka`, Testcontainers (`junit-jupiter`,
  `kafka`, `mongodb`), and Spock/Groovy (`spock-core` 2.3-groovy-4.0 + `gmavenplus-plugin`) to
  `adjudication-service`. New classes: `Claim`/`ClaimRepository` (same `claims` collection
  claims-intake-service writes to — a sanctioned same-collection write-back per §5.3, not a
  cross-service-read violation), `ClaimSubmittedEvent`, `ClaimAdjudicatedEvent`,
  `ClaimEventPublisher`, `Enrollment`/`EnrollmentClient`/`RestTemplateConfig` (REST call to
  Enrollment Service), `PlanLimitsProperties` (static config, not a new Mongo collection —
  4 entries don't earn a repository), `RuleEngine` (single class, three fixed checks: coverage,
  plan-limit, duplicate — no pluggable Rule interface, since three fixed checks don't earn that
  abstraction), `AdjudicationConsumer`, and `KafkaConsumerConfig` (first Kafka consumer in the
  repo — `ErrorHandlingDeserializer` + `JsonDeserializer` configured with
  `spring.json.use.type.headers=false` + `spring.json.value.default.type`, since the Phase 2
  producer disabled type headers; `DeadLetterPublishingRecoverer` publishing to
  `claim.submitted.DLT`, satisfying §4's "DLT recoverer on every consumer from day one").

  **Known limitation, stated deliberately, not an oversight**: `EnrollmentClient` fails closed —
  if Enrollment Service is unreachable, every claim is denied (not retried, not queued). This is
  a real business tradeoff (false denials during an outage vs. the risk of approving a claim
  with unverifiable coverage) — chosen because a wrongful denial is appealable/resubmittable,
  while a wrongful approval (payout) is not easily clawed back.

  Testing: `RuleEngineSpec.groovy` (Spock, 13 given/when/then cases covering coverage,
  plan-limit, duplicate, and multi-failure scenarios), Mockito unit tests for
  `EnrollmentClient`/`AdjudicationConsumer`/`ClaimEventPublisher`, one Testcontainers
  integration test (`AdjudicationIntegrationTest`, real Kafka + Mongo containers, Enrollment
  Service mocked) proving the full consume→adjudicate→publish pipeline — all 22 non-container
  tests plus the integration test passed. Broader integration-test hardening stays deferred to
  Phase 9 per the original plan; writing these two now (rather than only in Phase 9) was a
  scoping call, since they're the tests for code being written in this phase.

  `RuleEnginePrecisionRecallTest` ran 33 hand-labeled synthetic claims directly against
  `RuleEngine` (not through the live pipeline) — precision = 1.000, recall = 1.000
  (TP=15, FP=0, TN=18, FN=0), logged in §11.

  Verified live against the real running stack, not just unit-tested: rebuilt and restarted
  `adjudication-service` in `infra/docker-compose.yml` (added `KAFKA_BROKERS`,
  `ENROLLMENT_SERVICE_URL`, `depends_on: redpanda, enrollment-service`), connected to the real
  Atlas cluster and Redpanda. Seeded a real ACTIVE dental enrollment, submitted a within-limit
  claim via the live `POST /claims`, confirmed via Mongo the claim flipped to `APPROVED` with a
  populated `ruleTrace`, and confirmed via `rpk topic consume claim.adjudicated` that the
  published event matched §4's schema exactly (lowercase `status: "approved"`). Also verified,
  live: an over-limit claim → `DENIED` ("amount exceeds plan limit"); a claim with no enrollment
  → `DENIED` ("no active coverage"); an immediate resubmission of the same claim → `DENIED`
  ("possible duplicate submission"); stopping `enrollment-service` mid-flight → clean fail-closed
  `DENIED` with a warning logged, listener stayed alive (confirmed by resubmitting successfully
  after restarting `enrollment-service`); and a deliberately malformed message published
  straight to `claim.submitted` → landed on `claim.submitted.DLT` with the expected
  `DeserializationException` headers, confirming the DLT recoverer actually works. All test
  claims/enrollments created during manual verification were deleted from Atlas afterward.
- **2026-09-10** — Initial plan created.
- **2026-09-10** — Adopted PR-per-phase workflow: added §2.1 (Git workflow & branch protection — no direct commits to `main`, one branch per phase, PR + passing CI required to merge) and §9.1 (detailed GitHub Actions CI/CD pipeline, whose jobs become the required status checks). Phase 0's checklist updated to include configuring branch protection and verifying the CI workflow via an actual PR before later phases depend on it.
- **2026-09-10** — Clarified §2.1: Claude Code opens PRs but never merges them — merging is always a manual step the repo owner performs after reviewing the diff and confirming CI is green. Mirrored in `CLAUDE.md`.
- **2026-09-10** — Added dev environment note to §2: develop inside WSL2/Ubuntu (with VS Code Remote-WSL), not native Windows — driven by Testcontainers/Docker-socket and volume-mount performance considerations.
- **2026-09-10** — Phase 2 implemented on branch `phase-2-claims-intake-kafka` (issue #5). Added `spring-data-mongodb`, `spring-boot-starter-validation`, and `spring-kafka` to `claims-intake-service`; new `Claim` document (full §3 schema, with `adjudicatedAt`/`decisionReason`/`ruleTrace` left null for Phase 3), `ClaimRequest` (validated record for client input), `ClaimRepository`, `ClaimSubmittedEvent` (Kafka payload matching §4 exactly), `ClaimEventPublisher`, and `ClaimController` (`POST /claims`). `infra/docker-compose.yml`: `claims-intake-service` now gets `MONGODB_URI` via `.env` and `KAFKA_BROKERS=redpanda:9092`; Redpanda gets a named volume (`redpanda-data`) so topics/messages survive `docker compose up`/`down` cycles, which they didn't before. Topic creation intentionally kept out of the app (no `KafkaAdmin`/`NewTopic` bean) — created via `rpk topic create claim.submitted` against the running container, to avoid any risk of the existing `contextLoads` test blocking on broker discovery in CI (no broker exists there).

  Verified live, not just unit-tested: brought up real Redpanda + Redpanda Console + `claims-intake-service` (connected to Atlas), created the topic, POSTed a real claim, and consumed the message straight off `claim.submitted` via `rpk topic consume` — confirmed the key was the generated `claimId` and the JSON body matched. Caught and fixed a real bug this way: the first message serialized `submittedAt` as a Jackson epoch-timestamp number instead of the ISO-8601 string PLAN.md §4 specifies — fixed by having `ClaimSubmittedEvent` carry `submittedAt` as a pre-formatted string (`Instant.toString()`), re-verified on a second message that it now serializes correctly. Test claims created during manual verification were deleted from Atlas afterward so `claims` isn't polluted with non-synthetic data.
- **2026-09-10** — Phase 1 implemented on branch `phase-1-enrollment-service` (issue #3). Atlas M0 cluster (`claims-pipeline`, AWS us-east-1), network access, and app DB user (`claimspipelineapp`) were set up by the repo owner beforehand. Added `spring-boot-starter-data-mongodb` + `spring-boot-starter-validation` to `enrollment-service`; new `Enrollment` document model, `EnrollmentRepository`, and `EnrollmentController` (`POST /enrollments`, `GET /enrollments/{employeeId}`). Verified live against the real Atlas cluster (not just unit-tested): the container connected to Atlas replica set `atlas-pl3mbl-shard-0` and discovered the primary; `POST /enrollments` returned 201 with a Mongo-generated id, `GET /enrollments/{employeeId}` round-tripped it, an empty-body POST correctly returned 400. Ran `data/synthetic/init_collections.py` — created the 3 collections nothing wrote to yet (`claims`, `policy_documents`, `notifications_log`); `enrollments` already existed from the CRUD check. Ran `data/synthetic/seed_enrollments.py --count 30` against the running service — confirmed via a direct Atlas query (30 documents) and an API round-trip on one seeded `employeeId` (`EMP-25349`). A manual verification document created during CRUD testing was deleted afterward so `enrollments` holds only the 30 genuine synthetic records.
- **2026-09-10** — Phase 0 implemented on branch `phase-0-scaffold` (issue #1): repo folder structure scaffolded per §2 (stub Spring Boot services, FastAPI stub, Vite React stub, `infra/docker-compose.yml` + `docker-compose.prod.yml`, `.github/workflows/ci.yml`). Verified locally: `docker compose -f infra/docker-compose.yml up -d` starts all 7 containers (redpanda, redpanda-console, 4 Java services, rag-assistant-service, frontend) — enrollment-service `/actuator/health` returned 200, frontend and redpanda-console both returned 200. All CI-equivalent checks (checkstyle, `mvn test` per Java service, ruff, pytest, eslint, vitest, and all 6 `docker build`s) passed locally before push. PR #2 opened against `main`; all 12 CI jobs passed. Branch protection applied on `main`: PR required, all 12 CI jobs required as status checks, branches must be up to date, administrators included, 0 required approvals (solo repo), force-push and branch deletion disabled.