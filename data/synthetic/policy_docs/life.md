# Group Life Plan Summary

> **SYNTHETIC DATA — for portfolio/demo purposes only.** This document was generated for the
> Group Claims Pipeline portfolio project. It is not a real Mutual of Omaha or any other
> insurer's policy document, and describes no real coverage.

## Coverage Limits

The Group Life Plan pays eligible claims up to a maximum of $10,000.00 per claim. This synthetic
plan does not scale the benefit by salary multiple, age, or tenure — every active enrollee
carries the same flat $10,000.00 benefit cap, unlike many real-world group life products that
tie the benefit to a multiple of annual salary. A claim requesting more than $10,000.00 is
denied in full against this cap rather than paid up to the maximum, and the plan does not
prorate the benefit based on how long the enrollee had been covered before death. There is no
separate accidental death multiplier or supplemental rider modeled in this synthetic plan, so an
accidental death claim is evaluated against the same flat $10,000.00 cap as any other covered
death, without an additional accidental-death benefit layered on top.

## Exclusions

Death resulting from suicide within the first 24 months of the enrollment's effective date is
excluded from this plan's benefit, a standard contestability-period provision meant to limit
anti-selection by members enrolling with foreknowledge of an intent to end their life. Death
resulting from participation in an illegal act, or from an act of declared or undeclared war, is
excluded regardless of how much time has passed since the enrollment's effective date. Claims
filed by a beneficiary more than 365 days after the date of death are denied for late filing,
since the plan requires timely notice to process a claim against its records, and this window
is measured from the date of death itself rather than the date the beneficiary was notified or
located.

## Waiting Periods

A newly enrolled employee's coverage under this plan is subject to a 30-day waiting period from
the enrollment's effective date before any claim becomes eligible, mirroring the waiting period
applied uniformly across this synthetic plan's other lines of coverage. No waiting period is
applied to death resulting from a covered accidental injury sustained after the effective date,
since accidental deaths are, by construction, not foreseeable at the time of enrollment and do
not present the anti-selection risk the waiting period is designed to guard against. An employee
who re-enrolls after a lapse in coverage restarts the 30-day waiting period in full, regardless
of how long they were previously enrolled before the lapse.

## Definitions

"Active coverage" means an enrollment record with status `ACTIVE` as of the date of death, not
the date the claim was submitted, so a claim is evaluated against the enrollment status that was
in effect on the date of death even if that status has since changed in the system. "Beneficiary"
means the person or persons designated by the enrollee at the time of enrollment to receive the
plan's benefit; this synthetic plan does not model a default-beneficiary hierarchy for cases
where no designation was made, unlike a real plan which would typically fall back to the
enrollee's estate or a statutory order of relatives.
