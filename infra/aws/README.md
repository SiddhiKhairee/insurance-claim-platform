# AWS deployment

The whole stack runs on one EC2 instance with Docker Compose (`infra/docker-compose.prod.yml`). MongoDB stays on Atlas. See `PLAN.md` §8 for the design and §12 for the dated record of how it was set up. All data the deployed system handles is **synthetic**.

## What exists (created by hand in the AWS console, not by code in this repo)

| Resource | Setting |
|---|---|
| IAM user `claims-pipeline-dev` | EC2 + S3 only; used for creating resources instead of root |
| EC2 `claims-pipeline-prod` | `t3.medium` (4 GiB, sized from a measured ~2.45 GiB peak; **not free tier**), Ubuntu 26.04 LTS, us-east-1, 25 GB gp3 root volume |
| Elastic IP | Associated with the instance; the site is served at `http://<elastic-ip>` |
| Security group | 22 from the owner's IP only; 80 from anywhere; **nothing else** (no 443) |
| S3 bucket + instance role `claims-pipeline-ec2-s3-role` | `s3:PutObject`/`s3:GetObject` on that one bucket. **Not used yet:** attachment upload was deferred out of Phase 8 |
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
# Fill in .env: MONGODB_URI, GROQ_API_KEY, GEMINI_API_KEY, PUBLIC_ORIGIN=http://<elastic-ip>
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

```bash
docker compose -f infra/docker-compose.prod.yml ps        # all running; rag-assistant-service healthy
docker compose -f infra/docker-compose.prod.yml logs -f rag-assistant-service   # wait for "assistant ready"
docker stats --no-stream                                   # memory against the 4 GiB box
```

From outside, `http://<elastic-ip>` serves the app, and `/claims/` and `/assistant/` are forwarded by nginx (`infra/nginx/prod.conf`). The service ports (8081–8084, 8000, 9092) aren't published and aren't open in the security group, so a request to them from outside should time out.

## Deliberate limits

- **Plain HTTP, no TLS.** There's no domain and port 443 isn't open. That's acceptable for a synthetic-data demo; don't use it for anything real.
- **Deploy is `git pull` + build on the box.** The ECR + GitHub Actions `deploy` job in `PLAN.md` §9.1 isn't built. It's optional per §8.5, and the IAM user has no ECR access.
- **Logs** come from `docker compose logs`, not CloudWatch (§8.7 marks CloudWatch optional).
