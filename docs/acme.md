---
title: Acme Corp — Q3 Product Review
author: Ada Lovelace
date: 26 August 2026
theme: "night"
---

# Q3 Product Review

<!-- .slide: data-background-image="assets/acme/hero.jpg" data-background-opacity="0.35" data-transition="fade" -->

Revenue, **delivery**, and the *three bets* that carry us into [Q4](https://acme.example/q4).

Note: Ninety seconds of framing, then straight into the agenda.

# Agenda

- Where the numbers landed
- What shipped, and what it looks like
- Three bets for Q4
- Risks we are carrying

Note: Five beats. Hold questions until the risks slide.

# The Numbers

| Metric | Q2 FY26 | Q3 FY26 |
| --- | --- | --- |
| ARR | $96.0M | $118.4M |
| Net revenue retention | 112% | 119% |
| Gross margin | 61.0% | 65.2% |

### Follow-ups

1. Book the enrichment migration window.
2. Publish the replay retention policy.
3. Hire two platform engineers.

## Revenue Chart

![Bar chart of booked revenue](assets/acme/chart.png "Booked revenue, FY26 Q1–Q3")

## Growth Loop

![Throughput ticker loop](assets/acme/loop.gif)

Note: Two seconds of the live ticker, on a loop.

# The Product

The replay console shipped behind a flag on 4 July and went GA in week nine.

## Replay Console

![](assets/acme/demo.mp4)

## The New Chime

<!-- .slide: data-transition="zoom" -->

![](assets/acme/chime.mp3)

# The Deck Is Data

```clojure
(deck/slide :metrics
  (content/table ["Metric" "Q2" "Q3"]
                 [["ARR" "$96.0M" "$118.4M"]]))
```

A slide is a map; `deck/deck` validates the whole tree.

Note: Two files, one deck. The markup is a front end, not the model.

# Voice Of Customer

> We replaced four internal dashboards with one Acme replay link.

# Closing

<!-- .slide: data-background-color="#12100e" -->

Ship the replay lane. ~~Halve~~ Quarter the cost per event.

Note: End here. Do not advance into the appendix unless asked.
