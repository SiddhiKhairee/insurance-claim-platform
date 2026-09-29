# insurance-claim-platform
Event-driven group-benefits claims platform (Java/Spring Boot, Kafka, MongoDB, React) with a RAG assistant for claims and policy Q&amp;A, built as a portfolio project targeting insurance-industry backend roles.

> **All data here is synthetic.** Claims, enrollments, plan documents and appeal documents are
> generated or written for this project. None of it is real Mutual of Omaha data or any real
> person's data.

The full architecture, build plan and dated decision log are in [`PLAN.md`](PLAN.md). Deployment runbook: [`infra/aws/README.md`](infra/aws/README.md). A complete README with the architecture diagram and measured results is planned for Phase 10.

## How a claim is decided

1. A claim is submitted, and claims-intake-service publishes `claim.submitted`.
2. adjudication-service's **rule engine** approves or denies it. It's deterministic and records the rules it applied in `ruleTrace`. No LLM takes part in the decision.
3. notification-service logs a simulated notification (nothing is really sent).
4. The **assistant** (rag-assistant-service) explains decisions and answers questions about the synthetic plan documents. It never makes a decision. It refuses requests to make one, and it abstains unless its answer passes a groundedness check.

## Appeals and admin review

- **Appeal.** A claimant can appeal a DENIED claim from the claim's page (`/claims/<claimId>`), giving a reason and 1–3 supporting documents (PDF, PNG or JPEG, up to 5 MB each).
- **Storage.** Documents are stored in a private S3 bucket (a local volume in development). They're readable only through an admin-only endpoint.
- **Review.** An admin signs up at `/admin` with a signup code the site owner hands out, then reviews appealed claims. The review page shows the rule engine's decision, `decisionReason` and `ruleTrace`, plus the documents. The admin **upholds** or **overturns** the denial and must write a note.
- **The rule engine's record is never changed.** The claim keeps the engine's `status`, `decisionReason` and `ruleTrace` exactly as written. The appeal outcome is recorded separately, as a person's decision: reviewer, time and note. An overturned claim shows as "Approved on appeal", next to the engine's original denial.
- **The reviewer's note is shown to the claimant.** The admin form says so.
- **No LLM reads, summarizes or scores a supporting document, or decides or recommends an appeal outcome.**
  - The assistant is given only the appeal's status and dates, never the documents, references to them, or the reviewer's note.
  - It reports a recorded outcome after the engine's decision.
  - It refuses questions that ask it to predict or recommend an appeal outcome ("will my appeal be approved?").
- `claim.appeal-decided` is published when an appeal is decided, and notification-service logs it.

## Deliberate limits of the demo

- **Plain HTTP, no TLS** on the deployed site. Admin passwords and the signup code cross the network unencrypted, so don't reuse a password here. That's acceptable only because every piece of data is synthetic.
- **No claimant accounts.** Anyone with a claim's ID (a random UUID) can view that claim and appeal it.
- **Unauthenticated uploads.** Appeal uploads are rate-limited by nginx, and the bucket expires appeal documents after 7 days. The deployed instance is stopped when it isn't being demonstrated. The worst-case arithmetic is in [`infra/aws/README.md`](infra/aws/README.md).
- **The guardrail is best-effort.** Its question-matching rules can't cover every phrasing. The hard guarantee is architectural: the assistant has read-only access to claims, and no path to change a decision or an appeal.
