# AWS deployment

The whole stack runs on one EC2 instance with Docker Compose (`infra/docker-compose.prod.yml`). MongoDB stays on Atlas. See `PLAN.md` §8 for the design and §12 for the dated record of how it was set up. All data the deployed system handles is **synthetic**.

## What exists (created by hand in the AWS console, not by code in this repo)

| Resource | Setting |
|---|---|
| IAM user `claims-pipeline-dev` | EC2 + S3 only; used for creating resources instead of root |
| EC2 `claims-pipeline-prod` | `t3.medium` (4 GiB, sized from a measured ~2.45 GiB peak; **not free tier**), Ubuntu 26.04 LTS, us-east-1, 25 GB gp3 root volume |
| Elastic IP | Associated with the instance; the site is served at `http://<elastic-ip>` |
| Security group | 22 from the owner's IP only; 80 from anywhere; **nothing else** (no 443) |
| S3 bucket (private) + instance role `claims-pipeline-ec2-s3-role` | Holds appeal supporting documents under `appeals/<claimId>/<docId>` (Phase 8b). The role allows `s3:PutObject`/`s3:GetObject`/`s3:DeleteObject` on that one bucket's objects, nothing else. Block Public Access fully on. Lifecycle rule `expire-appeal-documents` expires `appeals/` objects after 7 days |
| Instance metadata options | IMDSv2 required, **hop limit 2** so containers can reach the instance role's credentials |
| AWS Budget | $4 monthly, alerts at 80% / 100% |
| Atlas Network Access | Elastic IP + the dev machine's IP only |

## Cost: the instance is stopped by default

Start it only for a deploy, verification or demo, and **stop it when that's done**. Even while it's stopped, the Elastic IP and the 25 GB EBS volume still cost a small amount.

To stop it, use the EC2 console (Instance state → Stop), or over SSH run `sudo shutdown -h now`. The instance is EBS-backed, so shutting down stops it rather than terminating it. Confirm it shows **stopped** in the console.

## First deploy

```bash
ssh -i ~/keys/claims-pipeline-key.pem ubuntu@<elastic-ip>
git clone https://github.com/SiddhiKhairee/insurance-claim-platform.git
cd insurance-claim-platform
cp .env.example .env && chmod 600 .env
# Fill in .env: MONGODB_URI, GROQ_API_KEY, GEMINI_API_KEY, PUBLIC_ORIGIN=http://<elastic-ip>,
# S3_BUCKET=<bucket>, ADMIN_TOKEN_SECRET=<openssl rand -base64 48>, ADMIN_SIGNUP_CODE=<long random>
# Write each line as KEY=value, with no spaces around '='.
docker compose -f infra/docker-compose.prod.yml --env-file .env up -d --build
```

`--env-file .env` is required. Without it, Compose looks for `.env` in `infra/` and stops with an error that `PUBLIC_ORIGIN` is unset. The first build takes a while: the RAG image is ~3.4 GB (CPU torch plus model weights).

## Redeploy

```bash
cd insurance-claim-platform && git pull
docker compose -f infra/docker-compose.prod.yml --env-file .env up -d --build
```

If a build fails partway and needs a retry, run `docker system prune` first to free the partial layers. 25 GB fills up quickly with the RAG image and build cache.

## Verify

Every `docker compose` command needs `--env-file .env`, not just `up`. The `PUBLIC_ORIGIN` check runs on every command, so `ps` and `logs` fail without it.

```bash
C="docker compose -f infra/docker-compose.prod.yml --env-file .env"
$C ps                                   # all running; rag-assistant-service healthy
$C logs -f rag-assistant-service        # wait for "assistant ready"
docker stats --no-stream                # memory against the 4 GiB box
```

From outside, `http://<elastic-ip>` serves the app, and `/claims/`, `/api/admin/` and `/assistant/` are forwarded by nginx (`infra/nginx/prod.conf`). The service ports (8081–8084, 8000, 9092) aren't published and aren't open in the security group, so a request to them from outside should time out.

Appeal documents (after an appeal is submitted from the site):

```bash
# From the box, through the instance role (no keys on disk):
docker run --rm amazon/aws-cli s3 ls s3://<bucket>/appeals/ --recursive
# If claims-intake-service logs a credentials error on upload, check the hop limit (step 3 below).
```

## Appeals, documents and admin accounts (Phase 8b)

Claimants can appeal a denied claim with 1–3 supporting documents (PDF/PNG/JPEG, 5 MB each). The documents go to the private bucket through claims-intake-service and the instance role. Admins view them only through `/api/admin/appeals/{claimId}/documents/{docId}`; the bucket is never public and the public claim response carries no storage keys.

### Console steps (owner, done once with the instance stopped)

1. **IAM: allow deletes on the one bucket.** IAM → Roles → `claims-pipeline-ec2-s3-role` → Permissions → the inline policy → Edit → JSON. The statement for the bucket's objects should read as follows (`<bucket>` is the real name; the Resource stays that one bucket's objects):
   ```json
   {
     "Effect": "Allow",
     "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
     "Resource": "arn:aws:s3:::<bucket>/*"
   }
   ```
   Save. The app uses DeleteObject to remove files from a failed or losing appeal; without it those deletes fail and are only logged.
2. **Lifecycle rule.** S3 → the bucket → Management → Create lifecycle rule. Name `expire-appeal-documents`; scope "Limit the scope using filters", prefix `appeals/`; action "Expire current versions of objects" after **7** days. If the bucket has versioning enabled, also add "Permanently delete noncurrent versions of objects" after 1 day. Then check Permissions → Block public access: all four settings on.
3. **Metadata hop limit.** EC2 → the instance → Actions → Instance settings → Modify instance metadata options. Keep IMDSv2 **Required**, set **Metadata response hop limit** to **2**, Save. Containers sit one network hop past the instance, and with a hop limit of 1 the AWS SDK inside claims-intake-service can't fetch the role's credentials. This works while the instance is stopped.
4. **Server `.env`.** Add `S3_BUCKET`, `ADMIN_TOKEN_SECRET` and `ADMIN_SIGNUP_CODE` (see First deploy). The owner picks the production `ADMIN_SIGNUP_CODE`; it's shared only with people who should become admins. To close sign-ups, remove it and restart claims-intake-service; existing admins keep working. The prod compose file refuses to start without all three.

### Upload cost bound

`POST /claims/{id}/appeal` has no login, so nginx rate-limits it: per client IP, 1 per minute with a burst of 3; and site-wide, 1 per minute with a burst of 10 (nginx can't express a slower rate). A rejected request gets 429 with a JSON error.

Worst case, as arithmetic, not a measurement: site-wide ≤ ~1,450 appeals/day × ≤ 15 MB of documents ≈ **≤ 22 GB/day**. With the 7-day lifecycle rule that's ≤ ~150 GB stored at the peak of a sustained, week-long attack. The real controls are that the instance is **stopped by default**, so nothing can be uploaded most of the time, and the $4 budget alarm. Check current S3 pricing before relying on a dollar figure.

### Cleaning up test documents

```bash
docker run --rm amazon/aws-cli s3 rm s3://<bucket>/appeals/<claimId>/ --recursive
docker run --rm amazon/aws-cli s3 ls s3://<bucket>/appeals/ --recursive   # expect no output
```

## Deliberate limits

- **Plain HTTP, no TLS.** There's no domain and port 443 isn't open. Admin passwords and the signup code cross the network unencrypted at sign-up and login. That's acceptable for a synthetic-data demo; don't use it for anything real, and don't reuse an admin password anywhere else.
- **No claimant accounts.** Whoever has a claim's ID (a random UUID) can view it and appeal it.
- **Login rate limiting is in memory** in claims-intake-service (5 failures per IP per 15 min), so it resets when the container restarts.
- **Deploy is `git pull` + build on the box.** The ECR + GitHub Actions `deploy` job in `PLAN.md` §9.1 isn't built. It's optional per §8.5, and the IAM user has no ECR access.
- **Logs** come from `docker compose logs`, not CloudWatch (§8.7 marks CloudWatch optional).
