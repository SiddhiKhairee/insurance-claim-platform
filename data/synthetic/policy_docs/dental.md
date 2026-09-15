# Group Dental Plan Summary

> **SYNTHETIC DATA — for portfolio/demo purposes only.** This document was generated for the
> Group Claims Pipeline portfolio project. It is not a real Mutual of Omaha or any other
> insurer's policy document, and describes no real coverage.

## Coverage Limits

The Group Dental Plan reimburses eligible dental claims up to a maximum of $2,000.00 per claim.
This limit applies uniformly across preventive, basic, and major dental services in this
synthetic plan — there is no tiered sub-limit distinguishing a routine cleaning from a root
canal, unlike many real-world dental plans that cap preventive care separately from major
restorative work. A claim requesting more than $2,000.00 is denied in full against the plan's
cap; the plan does not pay out a partial amount up to the limit, and it does not average the
cost of multiple procedures performed on the same visit to bring the total under the cap.
There is no separate annual aggregate cap distinct from the per-claim limit described here,
so a member who needs several expensive procedures across separate visits within the same
year files, and is evaluated on, one independent claim per visit rather than a running total.

## Exclusions

Cosmetic dental procedures, including teeth whitening and veneers placed for appearance rather
than function, are excluded from coverage, even when a treating dentist frames the procedure as
having some incidental functional benefit. Orthodontic treatment for members over the age of 19
is excluded, as this synthetic plan treats adult orthodontia as elective regardless of whether
the treatment addresses a bite alignment issue with functional consequences. Claims for
treatment received outside a licensed dental provider's care, or for over-the-counter dental
products such as whitening strips or store-bought mouthguards, are not covered. Any claim
submitted more than 180 days after the date of service is denied for late filing, and this
window is measured from the date service was rendered, not the date the provider's invoice was
issued or the date the member became aware of the cost.

## Waiting Periods

A newly enrolled employee must wait 30 days from the enrollment's effective date before any
dental claim becomes eligible for benefits. Major services (crowns, bridges, root canals) carry
an additional waiting period of 90 days from the effective date, reflecting the higher cost and
lower urgency of these procedures relative to preventive care — preventive care is not subject
to this extended waiting period, since encouraging early routine visits is consistent with the
plan's broader cost-management goals even during the initial enrollment window. A member who
switches from an individual dental plan directly into this group plan does not receive credit
toward either waiting period for time already served under the prior individual plan.

## Definitions

"Active coverage" means an enrollment record with status `ACTIVE` as of the date of service, not
the date the claim was submitted, meaning coverage that lapses between the date of service and
the date of filing does not retroactively invalidate an otherwise eligible claim. "Preventive
service" means routine cleanings, exams, and x-rays performed at intervals consistent with
standard dental care guidelines; "major service" means any procedure involving a crown, bridge,
root canal, or extraction beyond a simple tooth extraction, and is the category subject to the
plan's extended 90-day waiting period described above.
