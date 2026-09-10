"""Seeds synthetic enrollments through the Enrollment Service's POST /enrollments endpoint."""

import argparse
import random
from datetime import date, timedelta

import requests
from faker import Faker

PLAN_TYPES = ["disability", "dental", "vision", "life"]
STATUSES = ["ACTIVE", "ACTIVE", "ACTIVE", "TERMINATED"]

fake = Faker()


def random_enrollment() -> dict:
    return {
        "employeeId": fake.unique.bothify(text="EMP-#####"),
        "employer": fake.company(),
        "planType": random.choice(PLAN_TYPES),
        "effectiveDate": (date.today() - timedelta(days=random.randint(0, 730))).isoformat(),
        "status": random.choice(STATUSES),
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Seed synthetic enrollments via the Enrollment Service API."
    )
    parser.add_argument("--base-url", default="http://localhost:8081")
    parser.add_argument("--count", type=int, default=30)
    args = parser.parse_args()

    created = 0
    for _ in range(args.count):
        response = requests.post(
            f"{args.base_url}/enrollments", json=random_enrollment(), timeout=10
        )
        response.raise_for_status()
        created += 1
    print(f"Seeded {created} synthetic enrollments via {args.base_url}/enrollments")


if __name__ == "__main__":
    main()
