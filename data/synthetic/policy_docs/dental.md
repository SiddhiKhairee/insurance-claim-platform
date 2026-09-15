# Group Dental Plan Summary

> **SYNTHETIC DATA — for portfolio/demo purposes only.** This document was generated for the
> Group Claims Pipeline portfolio project. It is not a real Mutual of Omaha or any other
> insurer's policy document, and describes no real coverage.

## Coverage Limits

The Group Dental Plan reimburses eligible dental claims up to a maximum of $2,000.00 per claim.
This limit applies uniformly across preventive, basic, and major dental services in this
synthetic plan — there is no tiered sub-limit distinguishing a routine cleaning from a root
canal, unlike many real-world dental plans that cap preventive care separately from major
restorative work at a much lower figure. A single claim can bundle multiple procedures performed
during the same visit, such as a cleaning, an exam, and a filling, and the combined total for
that visit is what is measured against the $2,000.00 cap, not each procedure individually. A
claim requesting more than $2,000.00 is denied in full against the plan's cap; the plan does not
pay out a partial amount up to the limit, and it does not average the cost of multiple
procedures performed on the same visit to bring the total under the cap by, for example,
approving the cleaning and exam while denying only the filling. There is no separate annual
aggregate cap distinct from the per-claim limit described here, so a member who needs several
expensive procedures across separate visits within the same year files, and is evaluated on, one
independent claim per visit rather than a running total across the whole plan year. A member
who receives treatment from two different providers for what is clinically the same course of
care, such as a root canal started by a general dentist and completed by an endodontist, may
still have both claims evaluated independently against the cap rather than combined into one.

## Exclusions

Cosmetic dental procedures, including teeth whitening and veneers placed for appearance rather
than function, are excluded from coverage, even when a treating dentist frames the procedure as
having some incidental functional benefit such as improved bite alignment from veneer placement.
Orthodontic treatment for members over the age of 19 is excluded, as this synthetic plan treats
adult orthodontia as elective regardless of whether the treatment addresses a bite alignment
issue with functional consequences like jaw pain or uneven wear. Claims for treatment received
outside a licensed dental provider's care, or for over-the-counter dental products such as
whitening strips, store-bought mouthguards, or night guards purchased without a prescription for
diagnosed teeth grinding, are not covered; a custom night guard prescribed by a dentist for
diagnosed bruxism is covered as a basic service. Dental implants placed purely to replace a
missing tooth for cosmetic reasons, as opposed to restoring chewing function after an accident
or extraction necessitated by decay, are excluded from this synthetic plan's benefit. Any claim
submitted more than 180 days after the date of service is denied for late filing, and this
window is measured from the date service was rendered, not the date the provider's invoice was
issued, the date the member received an explanation of benefits, or the date the member became
aware of the out-of-pocket cost. Treatment received from a provider outside the member's home
state or country while traveling is not automatically excluded on that basis alone, provided the
provider holds a license recognized by the jurisdiction in which the treatment was performed and
the claim is otherwise filed within the standard 180-day window.

## Waiting Periods

A newly enrolled employee must wait 30 days from the enrollment's effective date before any
dental claim becomes eligible for benefits. Major services (crowns, bridges, root canals, and
extractions beyond a simple tooth extraction) carry an additional waiting period of 90 days from
the effective date, reflecting the higher cost and lower urgency of these procedures relative to
preventive care — preventive care and basic services such as fillings and simple extractions are
not subject to this extended waiting period, since encouraging early routine visits is
consistent with the plan's broader cost-management goals even during the initial enrollment
window. A member who switches from an individual dental plan directly into this group plan does
not receive credit toward either waiting period for time already served under the prior
individual plan, and a member re-enrolling after any lapse in this group plan's coverage restarts
both waiting periods from zero regardless of how long they were previously covered. A procedure
that begins as a basic service but is upgraded mid-treatment to a major service, for example a
filling that reveals the need for a root canal once the tooth is opened, is evaluated against
the major-service waiting period based on the date the major service was actually performed,
not the date the original, lower-tier procedure was scheduled or begun. A multi-visit major
service, such as a crown requiring a preparation visit followed by a separate cementation visit
several weeks later, is treated as a single claim measured against the 90-day waiting period
using the date of the first visit in that course of treatment, and is not split into two claims
evaluated against two different waiting-period snapshots. If an employee's waiting period would
otherwise end mid-procedure — for example, a root canal begun one week before the 90-day mark
and completed one week after it — the claim is evaluated as of the date the completed procedure
was billed, so the claim is treated as eligible in that scenario rather than denied for having
started before the waiting period technically closed.

## Definitions

"Active coverage" means an enrollment record with status `ACTIVE` as of the date of service, not
the date the claim was submitted, meaning coverage that lapses between the date of service and
the date of filing does not retroactively invalidate an otherwise eligible claim. "Preventive
service" means routine cleanings, exams, and x-rays performed at intervals consistent with
standard dental care guidelines, such as a cleaning and exam roughly every six months. "Basic
service" means fillings, simple extractions, and prescribed night guards for diagnosed
conditions — procedures that address existing decay or damage without the higher cost or
complexity of a major service. "Major service" means any procedure involving a crown, bridge,
root canal, dental implant restoring chewing function, or extraction beyond a simple tooth
extraction, and is the category subject to the plan's extended 90-day waiting period described
above. "Simple tooth extraction" means removal of a fully erupted tooth visible above the gumline
without the need for bone removal or sectioning, as distinguished from a surgical extraction of
an impacted tooth, which this plan classifies as a major service. A procedure not clearly falling
into one of these three categories is classified by the claims team based on the treating
provider's procedure code at the time of adjudication, using standard industry procedure-code
groupings rather than the provider's own informal description of the work performed. "Date of
service" means the calendar date the procedure was actually performed and billed, which is the
anchor date used throughout this document for the active-coverage check, the late-filing window,
and the major-service waiting period alike.
