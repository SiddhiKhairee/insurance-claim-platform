# Group Life Plan Summary

> **SYNTHETIC DATA — for portfolio/demo purposes only.** This document was generated for the
> Group Claims Pipeline portfolio project. It is not a real Mutual of Omaha or any other
> insurer's policy document, and describes no real coverage.

## Coverage Limits

The Group Life Plan pays eligible claims up to a maximum of $10,000.00 per claim. This synthetic
plan does not scale the benefit by salary multiple, age, or tenure — every active enrollee
carries the same flat $10,000.00 benefit cap, unlike many real-world group life products that
tie the benefit to a multiple of annual salary or reduce the benefit amount as an employee ages
past a certain threshold. A claim requesting more than $10,000.00 is denied in full against this
cap rather than paid up to the maximum, and the plan does not prorate the benefit based on how
long the enrollee had been covered before death, how much premium had been paid in, or the
enrollee's age at the time of death. There is no separate accidental death multiplier or
supplemental rider modeled in this synthetic plan, so an accidental death claim is evaluated
against the same flat $10,000.00 cap as any other covered death, without an additional
accidental-death-and-dismemberment benefit layered on top the way a real group life product
often structures a distinct AD&D rider. When a claim names multiple beneficiaries, the
$10,000.00 total is divided among them according to the percentages the enrollee specified at
enrollment, or divided equally if no percentages were specified, but the combined payout across
all named beneficiaries for a single covered death still cannot exceed the $10,000.00 cap. A
beneficiary designation left blank or naming a beneficiary who predeceased the enrollee is
handled outside this synthetic plan's automated cap rules and instead requires manual review, since
the plan does not model a default-beneficiary hierarchy, as described further under Definitions
below.

## Exclusions

Death resulting from suicide within the first 24 months of the enrollment's effective date is
excluded from this plan's benefit, a standard contestability-period provision meant to limit
anti-selection by members enrolling with foreknowledge of an intent to end their life; a suicide
occurring after the 24-month contestability period has fully elapsed is treated as a covered
death like any other, without further inquiry into intent. Death resulting from participation in
an illegal act, such as committing a felony, is excluded regardless of how much time has passed
since the enrollment's effective date, and this exclusion applies even if the illegal act itself
was not the direct medical cause of death, so long as it was a material contributing
circumstance. Death resulting from an act of declared or undeclared war, including active
participation in armed conflict as a combatant, is excluded, though death of a covered member
who is a civilian bystander affected by conflict-related conditions unrelated to combatant status
is evaluated under the plan's standard rules rather than the war exclusion. Claims filed by a
beneficiary more than 365 days after the date of death are denied for late filing, since the plan
requires timely notice to process a claim against its records, and this window is measured from
the date of death itself rather than the date the beneficiary was notified, the date a death
certificate was issued, or the date the beneficiary was legally located.

## Waiting Periods

A newly enrolled employee's coverage under this plan is subject to a 30-day waiting period from
the enrollment's effective date before any claim becomes eligible, mirroring the waiting period
applied uniformly across this synthetic plan's other lines of coverage. No waiting period is
applied to death resulting from a covered accidental injury sustained after the effective date,
meaning a fatal accident such as a traffic collision or an accidental fall occurring on day five
of coverage is still eligible for the full benefit, since accidental deaths are, by construction,
not foreseeable at the time of enrollment and do not present the anti-selection risk the waiting
period is designed to guard against. Death from a pre-existing terminal illness diagnosed before
the enrollment's effective date, by contrast, remains subject to the full 30-day waiting period
and is not treated as an unforeseeable accidental event. An employee who re-enrolls after a lapse
in coverage restarts the 30-day waiting period in full, regardless of how long they were
previously enrolled before the lapse, and regardless of whether the lapse was voluntary or caused
by an administrative processing delay. The waiting period runs independently of, and is not
combined with, the 24-month suicide contestability period described under Exclusions above; a
death within the first 30 days of enrollment is evaluated against the waiting period first, and
only a death that clears the waiting period is then checked against the contestability period
and the other exclusions. A death occurring precisely on the 30th day of coverage, counting the
effective date itself as day one, is treated as having cleared the waiting period rather than
falling one day short of it.

## Definitions

"Active coverage" means an enrollment record with status `ACTIVE` as of the date of death, not
the date the claim was submitted, so a claim is evaluated against the enrollment status that was
in effect on the date of death even if that status has since changed in the system, for example
if the enrollment was later marked `TERMINATED` after the member's employment formally ended
following their death. "Beneficiary" means the person or persons designated by the enrollee at
the time of enrollment to receive the plan's benefit; this synthetic plan does not model a
default-beneficiary hierarchy for cases where no designation was made, unlike a real plan which
would typically fall back to the enrollee's estate or a statutory order of relatives such as a
spouse, then children, then parents. "Covered accidental injury" means bodily harm caused by a
sudden, unexpected external event, distinct from a decline caused by an underlying illness or
degenerative condition, and is the category of death exempted from the plan's standard 30-day
waiting period described above. "Contestability period" means the 24-month window from the
enrollment's effective date during which the plan reserves the right to deny a suicide-related
death claim, as described under Exclusions above; the term is specific to the suicide exclusion
and is not used elsewhere in this synthetic plan to describe any other timing rule. "Effective
date" means the date recorded on the enrollment record as the start of active coverage, which is
also the anchor date used to calculate both the 30-day waiting period and the 24-month
contestability period described in this document.
