---
title: "Labeled Rating Scales"
status: active
description: "Store ratings as numbers, pick them by label — one :crud/scale mechanism for severity, effort, and mood"
created: 2026-07-31
updated: 2026-07-31
tags: [schema, crud]
priority: medium
---

# Labeled Rating Scales

## Intent

Ratings were split across two bad options. `symptom-log/severity-score` was a
`:number` rendered as a free `<input type="number" step="any">`, so nothing
stopped an arbitrary value and there was no cue for which number to pick.
`mood-log/valence` and friends were enums whose numbers were left implicit in
member ordering — the schema comment literally read *"enum positions map to 1-5
for analysis"*, meaning every future analysis had to re-derive them from
position.

Both problems have the same fix: store the number, put the label in schema
metadata, render a select that shows both.

## Specification

`:crud/scale` — a vector of `[number label]` pairs in schema properties on a
`:number` field. `render :number` (`crud/forms/inputs.clj`) checks for it and
renders a select whose option values are the numbers and whose option text is
`"5 — Medium"`. Absent the key, the field renders exactly as before, so every
other `:number` field is untouched.

`convert-field-value :number` needed no change — it already parses the submitted
string to a double.

Fields carrying a scale:

| Field | Shape | Points |
|---|---|---|
| `symptom-log/severity-score` | geometric | 1, 2, 3, 5, 8, 13 |
| `task/effort` | geometric | 1, 2, 3, 5, 8, 13 |
| `mood-log/stress` | zero + geometric | 0, 1, 2, 3, 5, 8 |
| `mood-log/valence` | linear, bipolar | −2 … +2 |
| `mood-log/arousal` | linear, bipolar | −2 … +2 |

The rule that governs which fields qualify lives in AGENTS.md under "Ratings —
ordinal enums stay enums", since it needs to be enforced on every future schema.

## Validation

- [x] `render-number-scale-test` in `test/…/crud/forms/inputs_test.clj` — option
      text, selection, long/double equivalence, off-scale prepend, legacy keyword
- [x] Full suite green (90 tests, 690 assertions)
- [x] m003 transform validated against the real export: 1,131/1,131 pass, and the
      severity distribution maps 1:1 onto the Airtable option counts
      (6/256/344/288/141/38 → 1/2/3/5/8/13, plus 58 unrated)
- [x] m006 transform validated: 24/24 pass, valence and arousal within −2…2
- [ ] Re-run m003 and m006 against dev and spot-check both forms in the UI
      (blocked on the RocksDB index lock — needs the dev server stopped)

## Scope

Not included: converting bm-log's ordinal enums (see below), a list-view
formatter that shows labels instead of bare numbers (`format-cell-value`
dispatches on `[type value ctx]` with no access to field opts, and the label is
an input cue rather than a display need), and any sweep of the pre-scale
`task/effort` values.

Also not included, but worth a follow-up: a **required** scale field with no
current value has no blank option, so the browser preselects the first one — a
new mood log opens with valence on `-2 — Very unpleasant`. This is not a
regression (`render :enum` has the same behaviour, and acknowledges it at
`inputs.clj:436`), but a centred scale makes the wrong default more conspicuous;
`0 — Neutral` is the sane opening position. Fixing it needs a way to say which
point is the default — an explicit `:crud/scale-default`, or a rule like
"closest to zero" — and applies to every required enum too, so it belongs in its
own change.

## Notes

### Considered and rejected: bm-log

`bristol`, `urgency`, `blood`, and `ease-of-passage` all look like scales and
none of them are. The test is whether the *spacing* between points means
something, not whether they have an order:

- **Bristol** is a published clinical instrument (Heaton & Lewis, 1997) where the
  number indexes one of seven reference pictures. It is a form taxonomy. Nobody
  averages Bristol scores because "mean Bristol 3.5" describes nothing physical,
  and type 4 is not one unit more of anything than type 3 — it is the next
  picture. The keywords already sort correctly (`:b1-…` through `:b7-…`).
- **urgency / blood / ease-of-passage** are ordered but unspaced, and every
  question worth asking of them ("how often was urgency severe", "was there any
  blood") is a `count` or `group-by` that works fine on keywords.

All four have production data, so the change would cost a migration to buy
nothing. The `:n-a` member each of them carries was the clincher rather than an
obstacle to work around: a field that needs to express "not applicable" is
telling you it is a category set, not a measurement.

### Descriptive vs. prescriptive scales

`severity-scale` and `effort-scale` share a shape but not an origin, and the
asymmetry is deliberate rather than an oversight.

Severity is **descriptive** — its labels are the Airtable pain scale's verbatim
single-select options, a vocabulary that already exists in 1,131 records, so it
inherits reality's quirks. Airtable numbered those labels 0.5/1/2/3/5/8, which is
1/2/3/5/8 with a 0.5 bolted on the bottom to fit something below "I feel it".
Effort is **prescriptive** — designed fresh with nothing written yet, so
"Trivial" simply takes the floor of the sequence at 1 and no fraction is needed.

Porting shifts every severity label one position up the Fibonacci sequence
(0.5→1, 1→2, … 8→13). Ratios barely move (2, 1.5, 1.67, 1.6, 1.63 against the old
2, 2, 1.5, 1.67, 1.6), so the interval spacing that makes the number worth
storing survives. `:airtable/original-rating` keeps each imported record's source
label, so the old numbering stays auditable per record, and no mixed-scale data
will ever exist because the remap lands before the prod port.

### Two scale families

"Consistent numbering" ended up meaning consistent *derivation* — each scale's
shape follows from what it measures — rather than one shared set of numbers.

**Geometric** for perceived magnitude, where subjective intensity follows a power
law and estimation coarsens as the quantity grows: severity, effort, stress.
Powers of two were considered and rejected — a constant 2× ratio jumps 2→4→8 and
skips the 3 and 5 where most values land.

**Linear** for the circumplex axes, where equal steps are the entire point:
valence and arousal are a coordinate plane, so quadrants fall out as sign pairs
and distance from the origin is intensity. Fibonacci spacing would make that
geometry lie. Published instruments (SAM, the Affect Grid, Warriner et al.) run
1–9 unipolar; −2…2 is a linear transform, so comparing against norms is a
rescale. Five points is deliberate: one self-report a few times a day does not
support nine.

Stress sits with the geometric family despite living next to the two axes,
because it is not a third circumplex dimension — it is an independent magnitude
rating. Its zero is an absence anchor below the Fibonacci run rather than a scale
point (a geometric scale cannot contain zero); it exists because a mood entry
gets logged whether or not there is stress to report. Symptom severity has no
zero for the mirror-image reason: a symptom log only exists when there is a
symptom, so absence of a log *is* the zero. A stored `0` stress is a real
observation, not missing data — excluding zeros is a query-time choice.

### Dropped fields

`symptom-log/severity` (`:mild`/`:moderate`/`:severe`) was computed from the
score by the ingester's `score->severity`, so it carried nothing the score did
not. Worse, it was **required** while the score was optional, so the 58 of 1,131
Airtable rows with no rating were being assigned a fabricated `:mild`. The score
stays optional and those rows now import with no severity at all, which is the
honest encoding.

`symptom-episode/overall-severity` went too — a manually maintained duplicate of
something derivable from the episode's logs, and free to drift from them. With
both gone, `severity-enum` leaves the codebase.

### task/effort: accretive, not converted

Tasks exist in production, so `task/effort` was not converted. `task/effort-score`
is a **new** attribute carrying the scale; the old enum stays in the schema,
marked deprecated and `:hide true` so forms no longer write to it. No migration
and no mixed-type field.

Nothing reads the deprecated field. It stays only because the map is `:closed
true` — dropping an attribute that live documents still carry would fail their
next write. Tasks that predate the scale therefore show no effort until one is
picked, which is the intended trade: a fallback chain would spread knowledge of
the old encoding across every reader to display a handful of stale values.

A first pass instead widened `task/effort` to `[:or :number [:enum :low :medium
:high]]`. That does keep old documents valid — `:db/op :update` re-validates the
merged doc, so the enum arm was load-bearing — but it leaves one attribute
holding two types indefinitely, which every future reader and analysis has to
know about. Owner's call, now the general rule in AGENTS.md: entities with
production data get an added attribute, never a rewritten one.

The renderer's legacy-keyword branch survives that change because in-place
conversion is still allowed for entities whose data has not been ported —
`symptom-log` and `mood-log` here. Until m003/m006 are re-run, the dev database
holds enum-shaped documents for both, and opening one in a form would otherwise
lose its value.
