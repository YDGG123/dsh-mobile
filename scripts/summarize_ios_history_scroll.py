#!/usr/bin/env python3
"""Evaluate DEBUG CADisplayLink callback pacing; never claim GPU/rendered FPS."""

import argparse
import json
import math
from pathlib import Path


GATE_POLICIES = ("legacy-arrival", "display-cadence")
EXPERIENCE_POLICIES = ("interaction-priority",)


def percentile(values, fraction):
    """Nearest-rank percentile: sorted(values)[ceil(n * fraction) - 1]."""
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def finite_number(value):
    return isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value)


def frame_summary(samples):
    intervals = [sample["callbackArrivalIntervalMs"] for sample in samples]
    timestamps = [sample["displayTimestampIntervalMs"] for sample in samples
                  if sample.get("displayTimestampIntervalMs") is not None]
    budgets = [sample["expectedDurationMs"] for sample in samples]
    full_intervals = [sample["callbackArrivalIntervalMs"] for sample in samples if not sample["activationClipped"]]
    def interval_statistics(values):
        return {
            "sample_count": len(values),
            "p50_ms": percentile(values, 0.5), "p95_ms": percentile(values, 0.95),
            "maximum_ms": max(values) if values else None,
            "over_33_3_ms_fraction": sum(value > 33.3 for value in values) / len(values) if values else None,
            "stalls_at_least_100_ms": sum(value >= 100 for value in values),
        }
    return {
        "sample_count": len(intervals),
        "first_callback_samples": sum(sample.get("isFirstCallback", False) for sample in samples),
        "comparable_display_timestamp_samples": len(timestamps),
        "p50_callback_arrival_interval_ms": percentile(intervals, 0.5),
        "p95_callback_arrival_interval_ms": percentile(intervals, 0.95),
        "maximum_callback_arrival_interval_ms": max(intervals) if intervals else None,
        "p50_display_timestamp_interval_ms": percentile(timestamps, 0.5),
        "p95_display_timestamp_interval_ms": percentile(timestamps, 0.95),
        "maximum_display_timestamp_interval_ms": max(timestamps) if timestamps else None,
        "effective_callback_rate_hz": len(full_intervals) * 1000 / sum(full_intervals) if sum(full_intervals) else None,
        "over_33_3_ms_fraction": sum(value > 33.3 for value in intervals) / len(intervals) if intervals else None,
        "stalls_at_least_100_ms": sum(value >= 100 for value in intervals),
        "nominal_duration_p50_ms": percentile(budgets, 0.5),
        "nominal_duration_p95_ms": percentile(budgets, 0.95),
        "callbacks_past_target_deadline": sum(sample["callbackDeadlineLatenessMs"] > 0 for sample in samples),
        # Keep all legacy fields above unchanged; make both timing sources explicit for every window.
        "arrival_statistics": interval_statistics(intervals),
        "display_timestamp_statistics": interval_statistics(timestamps),
    }


def gate_pass(summary, gate_policy="legacy-arrival"):
    if gate_policy == "display-cadence":
        display = summary["display_timestamp_statistics"]
        return (display["sample_count"] > 0 and display["p95_ms"] <= 16.7
                and display["over_33_3_ms_fraction"] <= 0.01
                and display["stalls_at_least_100_ms"] == 0
                and summary["stalls_at_least_100_ms"] == 0)
    if gate_policy != "legacy-arrival":
        raise ValueError("unknown gate policy")
    return (summary["sample_count"] > 0 and summary["p95_callback_arrival_interval_ms"] <= 16.7
            and summary["over_33_3_ms_fraction"] <= 0.01 and summary["stalls_at_least_100_ms"] == 0)


def pagination_v2(report, samples, events):
    starts = [event for event in events if event.get("phase") == "history_load_requested"]
    commits = [event for event in events if event.get("phase") == "history_commit_end"
               and type(event.get("insertedRows")) is int and event["insertedRows"] > 0]
    windows = []
    lifecycle_valid = bool(starts) and len(starts) == len(commits)
    seen = set()
    for commit in commits:
        ordinal = commit.get("loadOrdinal")
        matching = [event for event in starts if event.get("loadOrdinal") == ordinal]
        begins = [event for event in events if event.get("phase") == "history_commit_begin"
                  and event.get("loadOrdinal") == ordinal]
        if (type(ordinal) is not int or ordinal in seen or len(matching) != 1 or len(begins) != 1
                or not matching[0]["elapsedSeconds"] <= begins[0]["elapsedSeconds"] <= commit["elapsedSeconds"]):
            lifecycle_valid = False
            continue
        seen.add(ordinal)
        start = matching[0]["elapsedSeconds"]
        end = commit["elapsedSeconds"] + 1
        # 保留与窗口相交的完整回调间隔，尤其是第一个跨越settle终点的长停顿；绝不裁短卡顿。
        subset = [sample for sample in samples if sample["elapsedSeconds"] >= start
                  and sample["elapsedSeconds"] - sample["callbackArrivalIntervalMs"] / 1000 <= end]
        overlap = [sample for sample in subset if sample["isScrolling"] and sample["isHistoryLoading"]
                   and sample.get("loadOrdinal") == ordinal]
        complete_settle = (report["durationSeconds"] >= end and bool(subset)
                           and subset[-1]["elapsedSeconds"] >= end)
        windows.append({
            "load_ordinal": ordinal, "start_seconds": start, "commit_seconds": commit["elapsedSeconds"],
            "settle_end_seconds": end, "complete_settle": complete_settle,
            "scroll_pipeline_overlap_samples": len(overlap),
            "scroll_pipeline_overlap_seconds": sum(max(0, min(sample["elapsedSeconds"], commit["elapsedSeconds"])
                - max(start, sample["elapsedSeconds"] - sample["callbackArrivalIntervalMs"] / 1000)) for sample in overlap),
            "metrics": frame_summary(subset),
        })
    return commits, windows, lifecycle_valid, {}


REJECTION_REASONS = {"presentation_pending", "domain_loading", "no_more_history", "projection_pending", "other"}


def diagnostic_aggregates_v3(report, operations):
    """Validate exact high-frequency counts without consuming lifecycle-event capacity."""
    keys = ("approachCallCount", "droppedApproachAggregateCallCount", "droppedRejectedAggregateCallCount")
    if any(type(report.get(key)) is not int or report[key] < 0 for key in keys):
        raise ValueError("explicit nonnegative diagnostic aggregate counters required")
    if (report.get("diagnosticAggregateCapacity") != 128
            or type(report.get("diagnosticAggregateCapacity")) is not int
            or type(report.get("diagnosticAggregateLimitReached")) is not bool):
        raise ValueError("explicit 128-key diagnostic aggregate capacity and limit flag required")
    aggregates = report.get("diagnosticAggregates")
    if not isinstance(aggregates, list) or len(aggregates) > 128:
        raise ValueError("diagnostic aggregates must be an array of at most 128 keys")
    valid = True
    totals = {"history_approach": 0, "history_admission_rejected": 0}
    seen = set()
    by_ordinal = {operation["ordinal"]: operation for operation in operations}
    for aggregate in aggregates:
        phase, reason, ordinal = (aggregate.get(key) for key in ("phase", "reason", "loadOrdinal"))
        if (phase not in totals or type(aggregate.get("count")) is not int or aggregate["count"] <= 0
                or ordinal is not None and (type(ordinal) is not int or ordinal <= 0)
                or phase == "history_approach" and reason is not None
                or phase == "history_admission_rejected" and reason not in REJECTION_REASONS):
            raise ValueError("invalid aggregate kind/reason/ordinal/count")
        first, last = aggregate.get("firstElapsedSeconds"), aggregate.get("lastElapsedSeconds")
        if not finite_number(first) or not finite_number(last) or not 0 <= first <= last <= report["durationSeconds"]:
            raise ValueError("invalid aggregate first/last time")
        key = (phase, reason, ordinal)
        if key in seen:
            valid = False
        seen.add(key)
        totals[phase] += aggregate["count"]
        operation = by_ordinal.get(ordinal)
        if ordinal is not None:
            if (operation is None or first < operation["start"]
                    or operation["end"] is not None and last > operation["end"]):
                valid = False
        else:
            for at in (first, last):
                if any(operation["start"] <= at and (operation["end"] is None or at < operation["end"])
                       for operation in operations):
                    valid = False
    dropped_approaches = report["droppedApproachAggregateCallCount"]
    dropped_rejects = report["droppedRejectedAggregateCallCount"]
    if (totals["history_approach"] + dropped_approaches != report["approachCallCount"]
            or totals["history_admission_rejected"] + dropped_rejects != report["rejectedCallCount"]
            or report["diagnosticAggregateLimitReached"] != bool(dropped_approaches + dropped_rejects)):
        valid = False
    return valid, {
        "approach_calls": report["approachCallCount"], "rejected_calls": report["rejectedCallCount"],
        "aggregate_keys": len(aggregates), "aggregate_capacity": 128,
        "aggregate_limit_reached": report["diagnosticAggregateLimitReached"],
        "dropped_approach_calls": dropped_approaches, "dropped_rejected_calls": dropped_rejects,
    }


def pagination_v3(report, samples, events):
    """Reconstruct complete operations without letting one page end its parent pipeline."""
    count_keys = ("acceptedCallCount", "coalescedAcceptedCallCount", "rejectedCallCount",
                  "staleOperationFinishCount")
    if any(type(report.get(key)) is not int or report[key] < 0 for key in count_keys):
        raise ValueError("schema3 requires explicit nonnegative accepted/coalesced/rejected/stale counts")
    if report.get("operationTrackingMode") not in {"owned_admission", "observational_coalescing"}:
        raise ValueError("schema3 requires explicit operationTrackingMode")
    valid = (len(events) <= 256 and len(samples) <= 20_000
             and all(events[index]["elapsedSeconds"] <= events[index + 1]["elapsedSeconds"]
                     for index in range(len(events) - 1)))
    operations = []
    by_ordinal = {}
    active = None
    accepted = coalesced = stale = 0
    all_commits = []
    incomplete = []
    recognized = {"history_load_requested", "history_operation_required", "history_operation_terminal",
                  "history_operation_presented", "history_operation_complete", "history_commit_begin",
                  "history_commit_end", "history_operation_issued", "history_operation_committed",
                  "history_operation_finish_ignored"}
    for event in events:
        phase = event.get("phase")
        if phase in {"history_admission_rejected", "history_approach"}:
            # schema3 stores these high-frequency observations only in its separate bounded aggregates.
            valid = False
            continue
        if phase == "history_operation_finish_ignored":
            stale += 1
            continue
        if phase not in recognized:
            # Old consumer / operation acknowledgements are observational, never active acknowledgements.
            if phase in {"history_operation_required_ignored", "history_operation_terminal_ignored",
                         "history_operation_presented_ignored", "history_operation_issued_ignored",
                         "history_operation_committed_ignored"}:
                continue
            if isinstance(phase, str) and phase.startswith("history_operation_"):
                valid = False
            continue
        ordinal = event.get("loadOrdinal")
        if type(ordinal) is not int or ordinal <= 0:
            valid = False
            continue
        at = event["elapsedSeconds"]
        if phase == "history_load_requested":
            accepted += 1
            is_coalesced = active is not None
            coalesced += is_coalesced
            if (event.get("acceptedCallOrdinal") != accepted
                    or type(event.get("acceptedCallOrdinal")) is not int
                    or event.get("coalesced") is not is_coalesced):
                valid = False
            if active is None:
                if ordinal in by_ordinal:
                    valid = False
                    continue
                active = {"ordinal": ordinal, "start": at, "terminal": False, "required": None,
                          "issued": set(), "committed": {}, "presented": None, "presentation_evidence": [],
                          "commits": [], "pending": None,
                          "accepted_calls": 0, "end": None, "outcome": None}
                operations.append(active)
                by_ordinal[ordinal] = active
            elif active["ordinal"] != ordinal:
                valid = False
                continue
            active["accepted_calls"] += 1
            # A newly accepted internal batch invalidates the earlier batch's terminal observation.
            active["terminal"] = False
            continue
        if active is None or active["ordinal"] != ordinal:
            valid = False
            continue
        if phase in {"history_operation_required", "history_operation_presented",
                     "history_operation_issued", "history_operation_committed"}:
            epoch = event.get("projectionEpoch")
            if type(epoch) is not int or epoch < 0:
                valid = False
                continue
            if phase == "history_operation_required":
                if active["required"] is not None and epoch < active["required"]:
                    valid = False
                active["required"] = epoch
            elif phase == "history_operation_issued":
                if active["required"] is None or epoch < active["required"] or epoch in active["issued"]:
                    valid = False
                active["issued"].add(epoch)
            elif phase == "history_operation_committed":
                if epoch not in active["issued"] or active["pending"] is not None or epoch in active["committed"]:
                    valid = False
                active["committed"][epoch] = at
            else:
                if epoch not in active["issued"] or epoch not in active["committed"] or active["pending"] is not None:
                    valid = False
                active["presented"] = max(active["presented"] or 0, epoch)
                active["presentation_evidence"].append((epoch, at, active["committed"].get(epoch)))
        elif phase == "history_operation_terminal":
            active["terminal"] = True
        elif phase == "history_commit_begin":
            if active["pending"] is not None or type(event.get("insertedRows")) is not int or event["insertedRows"] < 0:
                valid = False
            active["pending"] = event
        elif phase == "history_commit_end":
            pending = active["pending"]
            if (pending is None or type(event.get("insertedRows")) is not int or event["insertedRows"] < 0
                    or pending.get("insertedRows") != event.get("insertedRows")):
                valid = False
            else:
                active["commits"].append(event)
                all_commits.append(event)
            active["pending"] = None
        elif phase == "history_operation_complete":
            outcome = event.get("outcome")
            if not active["terminal"] or active["pending"] is not None:
                valid = False
            if outcome == "presented":
                positive_commits = [commit for commit in active["commits"] if commit["insertedRows"] > 0]
                last_positive_commit = max((commit["elapsedSeconds"] for commit in positive_commits), default=None)
                if (active["required"] is None or not positive_commits
                        or not any(epoch >= active["required"] and committed_at is not None
                                   and last_positive_commit <= committed_at <= presented_at <= at
                                   for epoch, presented_at, committed_at in active["presentation_evidence"])):
                    valid = False
            elif outcome == "empty":
                if active["required"] is not None or active["commits"]:
                    valid = False
            elif outcome in {"failed", "cancelled", "detached"}:
                incomplete.append("operation_" + outcome)
            else:
                valid = False
            active["end"] = at
            active["outcome"] = outcome
            active = None
    if active is not None:
        incomplete.append("unfinished_operation")
        valid = False
    aggregates_valid, aggregate_detail = diagnostic_aggregates_v3(report, operations)
    valid = valid and aggregates_valid
    rejected = aggregate_detail["rejected_calls"]
    if report["diagnosticAggregateLimitReached"]:
        incomplete.append("diagnostic_aggregate_limit_reached")
    observed_counts = (accepted, coalesced, rejected, stale)
    if any(report[key] != count for key, count in zip(count_keys, observed_counts)):
        valid = False
    if report["operationTrackingMode"] == "owned_admission" and coalesced:
        # The optimized owner promises one admission until presentation; a repeated accept violates that promise.
        valid = False
    windows = []
    for operation in operations:
        if operation["end"] is None:
            continue
        start, completed = operation["start"], operation["end"]
        end = completed + 1
        subset = intersecting_samples(samples, start, end)
        overlap = [sample for sample in subset if sample["isScrolling"] and sample["isHistoryLoading"]
                   and sample.get("loadOrdinal") == operation["ordinal"]]
        commit_windows = []
        for commit in operation["commits"]:
            commit_end = commit["elapsedSeconds"] + 1
            commit_samples = intersecting_samples(samples, commit["elapsedSeconds"], commit_end)
            complete = (report["durationSeconds"] >= commit_end and bool(commit_samples)
                        and commit_samples[-1]["elapsedSeconds"] >= commit_end)
            commit_windows.append({"commit_seconds": commit["elapsedSeconds"],
                                   "inserted_rows": commit["insertedRows"], "complete_settle": complete,
                                   "settle_end_seconds": commit_end, "metrics": frame_summary(commit_samples)})
        complete_settle = (report["durationSeconds"] >= end and bool(subset)
                           and subset[-1]["elapsedSeconds"] >= end
                           and all(window["complete_settle"] for window in commit_windows))
        windows.append({"load_ordinal": operation["ordinal"], "start_seconds": start,
                        "operation_complete_seconds": completed, "outcome": operation["outcome"],
                        "accepted_calls": operation["accepted_calls"], "commit_count": len(operation["commits"]),
                        "required_projection_epoch": operation["required"],
                        "presented_projection_epoch": operation["presented"],
                        "issued_projection_epochs": sorted(operation["issued"]),
                        "committed_projection_epochs": sorted(operation["committed"]),
                        "settle_end_seconds": end, "complete_settle": complete_settle,
                        "scroll_pipeline_overlap_samples": len(overlap),
                        "scroll_pipeline_overlap_seconds": sum(max(0, min(sample["elapsedSeconds"], completed)
                            - max(start, sample["elapsedSeconds"] - sample["callbackArrivalIntervalMs"] / 1000))
                            for sample in overlap),
                        "commits": commit_windows, "metrics": frame_summary(subset)})
    # Independently verify that collection never clears the pipeline between two internal page commits.
    for sample in samples:
        pending = [operation for operation in operations if operation["start"] <= sample["elapsedSeconds"]
                   and (operation["end"] is None or sample["elapsedSeconds"] < operation["end"])]
        expected = pending[0]["ordinal"] if len(pending) == 1 else None
        if len(pending) > 1 or sample["isHistoryLoading"] != bool(pending):
            valid = False
        if sample.get("loadOrdinal") != expected:
            valid = False
    return all_commits, windows, valid and bool(operations), {
        "tracking_mode": report["operationTrackingMode"], "accepted_calls": accepted,
        "coalesced_accepted_calls": coalesced, "rejected_calls": rejected,
        "stale_operation_finishes": stale, "completed_operations": len(windows),
        "incomplete_operations": incomplete, "diagnostic_aggregation": aggregate_detail,
    }


def intersecting_samples(samples, start, end):
    # Keep the full crossing interval; clipping here could hide a stall immediately after settle.
    return [sample for sample in samples if sample["elapsedSeconds"] >= start
            and sample["elapsedSeconds"] - sample["callbackArrivalIntervalMs"] / 1000 <= end]


def interval_statistics(values):
    return {
        "sample_count": len(values), "p50_ms": percentile(values, .5), "p95_ms": percentile(values, .95),
        "maximum_ms": max(values) if values else None,
        "over_33_3_ms_count": sum(value > 33.3 for value in values),
        "over_33_3_ms_fraction": sum(value > 33.3 for value in values) / len(values) if values else None,
        "stalls_at_least_100_ms": sum(value >= 100 for value in values),
    }


def movement_windows(report, issues):
    """Use observed UIKit events, never a callback's arrival-time scrolling flag."""
    windows, started = [], None
    for event in report["events"]:
        phase, at = event.get("phase"), event["elapsedSeconds"]
        if phase == "scroll_begin":
            if started is not None:
                issues.append("nested_scroll_begin")
            else:
                started = at
        elif phase == "scroll_end":
            if started is None or at <= started:
                issues.append("unmatched_scroll_end")
            else:
                windows.append((started, at))
                started = None
    if started is not None:
        issues.append("unfinished_movement_window")
        windows.append((started, report["durationSeconds"]))
    if not windows:
        issues.append("missing_movement_windows")
    merged = []
    for start, end in windows:
        if end > report["durationSeconds"]:
            issues.append("movement_outside_capture")
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return merged


def interaction_clocks(report, movement, issues):
    """Classify each original interval independently; never clip boundary stalls."""
    intervals = {"arrival": [], "display": []}
    # The collector keeps its clocks ticking while inactive, but does not store idle-only
    # callbacks. A later activation must not invent one long interval across those callbacks.
    protected = list(movement)
    pending = {}
    for event in report.get("events", []):
        ordinal, phase = event.get("loadOrdinal"), event.get("phase")
        if phase == "history_load_requested":
            pending.setdefault(ordinal, event["elapsedSeconds"])
        elif phase == "history_operation_complete" and ordinal in pending:
            protected.append((pending.pop(ordinal), event["elapsedSeconds"] + 1))
    protected.extend((start, report.get("durationSeconds", math.inf)) for start in pending.values())
    omitted_inactive_gaps = 0
    previous_display = previous_arrival = None
    for index, sample in enumerate(report["samples"]):
        arrival = sample["elapsedSeconds"]
        stored_delta = sample["callbackArrivalIntervalMs"]
        first = sample.get("isFirstCallback")
        prior = sample.get("previousArrivalElapsedSeconds")
        if prior is not None:
            if not finite_number(prior) or prior >= arrival:
                issues.append("invalid_previous_callback_arrival_clock")
                arrival_start = arrival - stored_delta / 1000
            else:
                arrival_start = prior
                if (sample.get("activationClipped") is not True
                        and abs((arrival - prior) * 1000 - stored_delta) > .001
                        or stored_delta > (arrival - prior) * 1000 + .001):
                    issues.append("inconsistent_original_arrival_delta")
            if first:
                issues.append("first_callback_with_previous_arrival")
        elif first:
            arrival_start = 0
        else:
            arrival_start = arrival - stored_delta / 1000
            if sample.get("activationClipped") is True:
                issues.append("missing_previous_arrival_for_activation_clipped_sample")
        delta = (arrival - arrival_start) * 1000
        if delta <= 0 or previous_arrival is not None and arrival <= previous_arrival:
            issues.append("invalid_arrival_clock_sequence")
        inactive_gap = False
        if previous_arrival is not None and abs((arrival - previous_arrival) * 1000 - delta) > .001:
            if (sample.get("activationClipped") is True and arrival_start > previous_arrival
                    and not any(max(previous_arrival, a) < min(arrival_start, b) for a, b in protected)):
                inactive_gap = True
                omitted_inactive_gaps += 1
            else:
                issues.append("inconsistent_arrival_clock_delta")
        intervals["arrival"].append((arrival_start, arrival, delta))
        previous_arrival = arrival
        display = sample.get("displayTimestampElapsedSeconds")
        if type(first) is not bool or first and index != 0:
            issues.append("missing_or_misplaced_first_callback")
        if not finite_number(display):
            issues.append("missing_or_invalid_display_absolute_clock")
            previous_display = None
            continue
        if display > arrival + .001:
            issues.append("display_clock_after_callback_arrival")
        display_delta = sample.get("displayTimestampIntervalMs")
        if first:
            if display_delta is not None:
                issues.append("fabricated_first_display_interval")
        elif (not finite_number(display_delta) or display_delta <= 0
              or previous_display is not None and display <= previous_display):
            issues.append("invalid_comparable_display_clock")
        else:
            display_start = display - display_delta / 1000
            if (previous_display is not None and abs((display - previous_display) * 1000 - display_delta) > .001
                    and not (inactive_gap and display_start > previous_display)):
                issues.append("inconsistent_display_clock_delta")
            if index == 0 and sample.get("activationClipped") is not True:
                issues.append("unexplained_first_stored_callback")
            intervals["display"].append((display_start, display, display_delta))
        previous_display = display
    output = {}
    for clock, values in intervals.items():
        moving, idle, crossing, touching, multi = [], [], 0, 0, 0
        for start, end, delta in values:
            overlaps = [(a, b) for a, b in movement if start <= b and end >= a]
            (moving if overlaps else idle).append(delta)
            crossing += any(start < boundary < end for window in overlaps for boundary in window)
            touching += bool(overlaps) and not any(max(start, a) < min(end, b) for a, b in overlaps)
            multi += len(overlaps) > 1
        output[clock] = {
            "all": interval_statistics([value[2] for value in values]),
            "movement": interval_statistics(moving), "idle": interval_statistics(idle),
            "boundary_crossing_intervals": crossing, "boundary_touch_only_intervals": touching,
            "multiple_movement_windows_intervals": multi,
            "omitted_inactive_gaps": omitted_inactive_gaps,
        }
    return output, intervals


SCHEDULER_STAGES = {"accepted", "target_buffered", "idle_wait", "interrupted", "prepare_admitted",
                    "preparation_finished", "commit_admitted", "batch_presented", "final_ack",
                    "cancelled", "idle_drained"}


def scheduler_evidence(report, baseline, movement, issues, failures):
    events = report.get("schedulerEvents")
    limited = report.get("schedulerEventLimitReached")
    if not isinstance(events, list) or type(limited) is not bool:
        issues.append("missing_scheduler_evidence")
        return {"events_observed": 0, "operations": [], "batches": []}
    if limited or len(events) > 256:
        issues.append("scheduler_event_limit_reached")
    starts = {}
    for event in report["events"]:
        if event.get("phase") == "history_load_requested":
            starts.setdefault(event["loadOrdinal"], event["elapsedSeconds"])
    operations = {ordinal: {"load_ordinal": ordinal, "accepted_seconds": start,
                            "buffered_seconds": None, "first_presentation_seconds": None,
                            "final_ack_seconds": None, "interruption_count": 0,
                            "maximum_remaining_entries": 0, "remaining_entries": None,
                            "accepted_age_ms_maximum": 0, "buffered_age_ms_maximum": None,
                            "latest_generation": None, "accepted_observed": False,
                            "known_generations": set(), "acknowledgements": [],
                            "pending_preparation": None, "pending_commit": None,
                            "batch_count": 0, "inserted_entries": 0}
                  for ordinal, start in starts.items()}
    batches, prep_durations, admissions, previous_time = [], [], 0, -math.inf
    route_counts = {}
    for event in events:
        if not isinstance(event, dict):
            issues.append("invalid_scheduler_event")
            continue
        stage, at, ordinal = event.get("stage"), event.get("elapsedSeconds"), event.get("loadOrdinal")
        integers = ("generation", "entries", "batchEntries", "insertedEntries", "remainingEntries", "changedExistingCount")
        if (stage not in SCHEDULER_STAGES or not finite_number(at) or not 0 <= at <= report["durationSeconds"]
                or any(type(event.get(key)) is not int or event[key] < 0 for key in integers)
                or type(event.get("moving")) is not bool
                or ordinal is not None and (type(ordinal) is not int or ordinal <= 0)):
            issues.append("invalid_scheduler_event")
            continue
        if at < previous_time:
            issues.append("out_of_order_scheduler_events")
        previous_time = at
        if event["remainingEntries"] > event["entries"] or event["insertedEntries"] > event["batchEntries"]:
            issues.append("inconsistent_scheduler_entry_counts")
        route = event.get("route")
        if route is not None and route not in {"prepend", "replacement", "in_place", "initial"}:
            issues.append("unknown_scheduler_route")
        if stage in {"commit_admitted", "batch_presented"}:
            split = (event.get("insertedBodyEntries"), event.get("insertedSupplementalEntries"))
            if (any(type(count) is not int or count < 0 for count in split)
                    or sum(split) != event["insertedEntries"]):
                issues.append("missing_or_inconsistent_inserted_row_classification")
            if route is None:
                issues.append("missing_explicit_presentation_route")
            elif route == "prepend" and event["insertedEntries"] > 2:
                failures.append("prepend_batch_over_two_display_rows")
            elif route == "in_place" and event["insertedEntries"]:
                issues.append("in_place_route_with_inserted_rows")
            if stage == "commit_admitted":
                route_counts[route or "missing"] = route_counts.get(route or "missing", 0) + 1
        if stage in {"prepare_admitted", "commit_admitted"}:
            if event["moving"] or any(a <= at <= b for a, b in movement):
                admissions += 1
                failures.append("movement_preparation_or_commit_admission")
            last_end = max((b for _, b in movement if b <= at), default=None)
            if last_end is not None and at - last_end < .12 - .000001:
                failures.append("admission_before_continuous_120ms_idle")
            if stage == "prepare_admitted" and event["batchEntries"] != 1:
                failures.append("preparation_batch_not_one_display_row")
        operation = operations.get(ordinal)
        if ordinal is not None and operation is None:
            issues.append("scheduler_unknown_operation")
            continue
        if operation is None:
            if stage == "accepted":
                issues.append("scheduler_missing_operation_ordinal")
            continue
        completed_at = next((item["elapsedSeconds"] for item in report["events"]
                             if item.get("phase") == "history_operation_complete" and item.get("loadOrdinal") == ordinal), None)
        if completed_at is not None and at > completed_at:
            issues.append("scheduler_work_after_operation_complete")
        epoch = event.get("projectionEpoch")
        if stage in {"commit_admitted", "batch_presented", "final_ack"} and (type(epoch) is not int or epoch < 0):
            issues.append("missing_scheduler_projection_epoch")
        if stage == "commit_admitted":
            issued = any(item.get("phase") == "history_operation_issued" and item.get("loadOrdinal") == ordinal
                         and item.get("projectionEpoch") == epoch and item["elapsedSeconds"] <= at
                         for item in report["events"])
            requirements = [item["projectionEpoch"] for item in report["events"]
                            if item.get("phase") == "history_operation_required" and item.get("loadOrdinal") == ordinal
                            and item["elapsedSeconds"] <= at]
            if not issued or type(epoch) is not int or not requirements or epoch < max(requirements):
                issues.append("commit_admission_without_current_issued_history_epoch")
        age = event.get("acceptedAgeMs")
        if not finite_number(age) or age < 0 or abs(age - (at - operation["accepted_seconds"]) * 1000) > 1:
            issues.append("missing_or_reset_accepted_age")
        else:
            operation["accepted_age_ms_maximum"] = max(operation["accepted_age_ms_maximum"], age)
        operation["remaining_entries"] = event["remainingEntries"]
        operation["maximum_remaining_entries"] = max(operation["maximum_remaining_entries"], event["remainingEntries"])
        if stage == "accepted":
            if operation["accepted_observed"] or abs(at - operation["accepted_seconds"]) > .001:
                issues.append("duplicate_or_mistimed_scheduler_accepted")
            operation["accepted_observed"] = True
        elif not operation["accepted_observed"]:
            issues.append("scheduler_before_accepted")
        if stage == "target_buffered":
            generation = event["generation"]
            if operation["latest_generation"] is not None and generation < operation["latest_generation"]:
                issues.append("decreasing_target_generation")
            operation["latest_generation"] = generation
            operation["known_generations"].add(generation)
            if operation["buffered_seconds"] is None:
                operation["buffered_seconds"] = at
        if operation["buffered_seconds"] is not None:
            buffered_age = event.get("bufferedAgeMs")
            expected_age = (at - operation["buffered_seconds"]) * 1000
            if not finite_number(buffered_age) or buffered_age < 0 or abs(buffered_age - expected_age) > 1:
                issues.append("missing_or_reset_buffered_age")
            else:
                operation["buffered_age_ms_maximum"] = max(operation["buffered_age_ms_maximum"] or 0, buffered_age)
        if stage == "interrupted":
            operation["interruption_count"] += 1
        if stage == "prepare_admitted":
            if operation["pending_preparation"] is not None:
                issues.append("overlapping_scheduler_preparations")
            operation["pending_preparation"] = event
        elif stage == "preparation_finished":
            start = operation["pending_preparation"]
            if start is None or start["generation"] != event["generation"] or event["batchEntries"] != 1:
                issues.append("unpaired_scheduler_preparation")
            else:
                prep_durations.append((at - start["elapsedSeconds"]) * 1000)
            operation["pending_preparation"] = None
        elif stage == "commit_admitted":
            if operation["pending_commit"] is not None:
                issues.append("overlapping_scheduler_commits")
            if operation["pending_preparation"] is not None:
                issues.append("commit_before_preparation_finished")
            operation["pending_commit"] = event
        elif stage == "batch_presented":
            start = operation["pending_commit"]
            if (start is None or start["generation"] != event["generation"] or event["insertedEntries"] <= 0
                    or start["insertedEntries"] != event["insertedEntries"]
                    or start.get("insertedBodyEntries") != event.get("insertedBodyEntries")
                    or start.get("insertedSupplementalEntries") != event.get("insertedSupplementalEntries")
                    or start["batchEntries"] != event["batchEntries"]
                    or start.get("projectionEpoch") != epoch
                    or start.get("route") != route or start["changedExistingCount"] != event["changedExistingCount"]):
                issues.append("unpaired_scheduler_presentation")
            else:
                batches.append({"load_ordinal": ordinal, "generation": event["generation"],
                                "admitted_seconds": start["elapsedSeconds"], "presented_seconds": at,
                                "duration_ms": (at - start["elapsedSeconds"]) * 1000,
                                "inserted_entries": event["insertedEntries"],
                                "projection_epoch": epoch,
                                "remaining_entries": event["remainingEntries"],
                                "batch_entries": event["batchEntries"], "route": route,
                                "inserted_body_entries": event.get("insertedBodyEntries"),
                                "inserted_supplemental_entries": event.get("insertedSupplementalEntries"),
                                "changed_existing_count": event["changedExistingCount"]})
            is_body_batch = type(event.get("insertedBodyEntries")) is int and event["insertedBodyEntries"] > 0
            if event.get("firstPresentation") is not (is_body_batch and operation["first_presentation_seconds"] is None):
                issues.append("incorrect_first_presentation_flag")
            if is_body_batch and operation["first_presentation_seconds"] is None:
                operation["first_presentation_seconds"] = at
            operation["batch_count"] += 1
            operation["inserted_entries"] += event["insertedEntries"]
            operation["pending_commit"] = None
        elif stage == "final_ack":
            if (event["remainingEntries"] != 0 or event["generation"] not in operation["known_generations"]):
                issues.append("premature_or_stale_final_ack")
            pending = operation["pending_commit"]
            if pending is not None and pending["insertedEntries"] == 0:
                if pending["generation"] != event["generation"] or pending.get("projectionEpoch") != epoch:
                    issues.append("stale_zero_inserted_final_commit")
                batches.append({"load_ordinal": ordinal, "generation": event["generation"],
                                "admitted_seconds": pending["elapsedSeconds"], "presented_seconds": at,
                                "duration_ms": (at - pending["elapsedSeconds"]) * 1000,
                                "inserted_entries": 0, "remaining_entries": event["remainingEntries"],
                                "projection_epoch": pending.get("projectionEpoch"),
                                "batch_entries": pending["batchEntries"], "route": pending.get("route"),
                                "changed_existing_count": pending["changedExistingCount"]})
                operation["pending_commit"] = None
            if operation["pending_commit"] is not None or operation["pending_preparation"] is not None:
                issues.append("final_ack_with_unpresented_batch")
            operation["final_ack_seconds"] = at
            operation["acknowledgements"].append({"seconds": at, "projection_epoch": epoch,
                                                  "generation": event["generation"]})
        elif stage == "cancelled":
            issues.append("scheduler_operation_cancelled")
    windows = {window["load_ordinal"]: window for window in baseline["pagination_windows"]}
    actual_pairs, pending_actual = {}, {}
    for event in report["events"]:
        ordinal = event.get("loadOrdinal")
        if event.get("phase") == "history_commit_begin":
            pending_actual[ordinal] = event
            if any(a <= event["elapsedSeconds"] <= b for a, b in movement):
                failures.append("actual_commit_begin_during_movement")
        elif event.get("phase") == "history_commit_end" and ordinal in pending_actual:
            actual_pairs.setdefault(ordinal, []).append((pending_actual.pop(ordinal), event))
    for ordinal, operation in operations.items():
        window = windows.get(ordinal)
        if not operation.pop("accepted_observed"):
            issues.append("missing_scheduler_accepted")
        if operation.pop("pending_preparation") is not None or operation.pop("pending_commit") is not None:
            issues.append("unfinished_scheduler_work")
        operation.pop("known_generations")
        completed = window["operation_complete_seconds"] if window else None
        operation["operation_complete_seconds"] = completed
        operation["outcome"] = window["outcome"] if window else "unfinished"
        final = operation["final_ack_seconds"]
        first = operation["first_presentation_seconds"]
        if window is not None and window["outcome"] == "empty":
            if first is not None or final is not None or operation["batch_count"]:
                issues.append("empty_operation_with_synthetic_presentation")
        elif window is None or window["outcome"] != "presented" or first is None or final is None:
            issues.append("missing_final_actual_presentation")
        else:
            required = window.get("required_projection_epoch")
            used_presentations = set()
            for ack in operation["acknowledgements"]:
                epoch, ack_at = ack["projection_epoch"], ack["seconds"]
                requirements = [item["projectionEpoch"] for item in report["events"]
                                if item.get("phase") == "history_operation_required" and item.get("loadOrdinal") == ordinal
                                and item["elapsedSeconds"] <= ack_at]
                evidence = [item for item in report["events"]
                            if item.get("phase") == "history_operation_presented" and item.get("loadOrdinal") == ordinal
                            and item.get("projectionEpoch") == epoch and ack_at <= item["elapsedSeconds"] <= completed
                            and (epoch, item["elapsedSeconds"]) not in used_presentations]
                if (type(epoch) is not int or not requirements or epoch < max(requirements) or not evidence):
                    issues.append("scheduler_ack_not_backed_by_its_actual_epoch")
                elif evidence:
                    used_presentations.add((epoch, evidence[0]["elapsedSeconds"]))
            latest_acks = [ack["seconds"] for ack in operation["acknowledgements"]
                           if type(ack["projection_epoch"]) is int and type(required) is int and ack["projection_epoch"] >= required]
            final = max(latest_acks, default=None)
            operation["final_ack_seconds"] = final
            matched = [event for event in report["events"] if event.get("phase") == "history_operation_presented"
                       and event.get("loadOrdinal") == ordinal and event.get("projectionEpoch") is not None
                       and type(required) is int and event["projectionEpoch"] >= required
                       and final is not None and final <= event["elapsedSeconds"] <= completed]
            commits = window["commits"]
            if (not matched or not commits or final is None or not first <= final <= completed
                    or final < max(commit["commit_seconds"] for commit in commits)
                    or operation["inserted_entries"] != sum(commit["inserted_rows"] for commit in commits)):
                issues.append("scheduler_final_ack_not_backed_by_actual_lifecycle")
            actual = [(commit["commit_seconds"], commit["inserted_rows"]) for commit in commits if commit["inserted_rows"] > 0]
            scheduled = [(batch["presented_seconds"], batch["inserted_entries"]) for batch in batches
                         if batch["load_ordinal"] == ordinal and batch["inserted_entries"] > 0]
            if len(actual) != len(scheduled) or any(a[0] > b[0] or a[1] != b[1] for a, b in zip(actual, scheduled)):
                issues.append("scheduler_batches_not_backed_by_actual_commits")
            pairs = [(begin, end) for begin, end in actual_pairs.get(ordinal, []) if end["insertedRows"] > 0]
            scheduled_batches = [batch for batch in batches if batch["load_ordinal"] == ordinal and batch["inserted_entries"] > 0]
            if len(pairs) != len(scheduled_batches) or any(
                    batch["admitted_seconds"] > begin["elapsedSeconds"]
                    or begin["elapsedSeconds"] > batch["presented_seconds"]
                    or (index + 1 < len(pairs) and batch["presented_seconds"] > pairs[index + 1][0]["elapsedSeconds"])
                    or completed is not None and batch["presented_seconds"] > completed
                    for index, ((begin, _), batch) in enumerate(zip(pairs, scheduled_batches))):
                issues.append("scheduler_admission_not_before_actual_commit")
        observed_until = completed if completed is not None else report["durationSeconds"]
        operation["pending_age_ms_at_completion_or_capture_end"] = (observed_until - operation["accepted_seconds"]) * 1000
        operation["first_presentation_delay_ms"] = (first - operation["accepted_seconds"]) * 1000 if first is not None else None
        operation["final_presentation_delay_ms"] = (final - operation["accepted_seconds"]) * 1000 if final is not None else None
        operation["operation_completion_delay_ms"] = (completed - operation["accepted_seconds"]) * 1000 if completed is not None else None
    return {"events_observed": len(events), "event_capacity": 256, "event_limit_reached": limited,
            "movement_admission_violations": admissions, "batches": batches,
            "presentation_route_counts": route_counts,
            "replacement_batches": [batch for batch in batches if batch["route"] == "replacement"],
            "maximum_replacement_batch_entries": max((batch["batch_entries"] for batch in batches
                                                        if batch["route"] == "replacement"), default=None),
            "route_evidence_limitation": "Routes are explicit producer diagnostics; raw omits item IDs so suffix/order truth is verified by source/native tests, not inferred here. Replacement/initial are not called bounded prepends.",
            "maximum_batch_duration_ms": max((batch["duration_ms"] for batch in batches), default=None),
            "preparation_row_durations_ms": prep_durations,
            "maximum_preparation_row_duration_ms": max(prep_durations, default=None),
            "operations": list(operations.values())}


def interaction_experience(report, baseline):
    issues, failures = [], []
    movement = movement_windows(report, issues)
    clocks, intervals = interaction_clocks(report, movement, issues)
    if report["schemaVersion"] != 3 or report.get("operationTrackingMode") != "owned_admission":
        issues.append("interaction_priority_requires_owned_schema3")
    issues.extend(baseline["incomplete_or_truncated"])
    if not baseline["lifecycle_valid"] or not baseline["completed_page_commits"] or baseline["inserted_rows"] <= 0:
        issues.append("missing_complete_positive_pagination")
    if len(report["samples"]) < 120 or clocks["display"]["movement"]["sample_count"] < 30:
        issues.append("insufficient_observed_movement_samples")
    coverage = []
    for window in baseline["pagination_windows"]:
        start = window["start_seconds"]
        end = window.get("operation_complete_seconds", window.get("commit_seconds"))
        overlaps = sum(any(max(a, start, lo) < min(b, end, hi) for lo, hi in movement)
                       for a, b, _ in intervals["arrival"])
        coverage.append({"load_ordinal": window["load_ordinal"], "overlapping_arrival_intervals": overlaps})
        if not window["complete_settle"] or window.get("outcome", "presented") == "presented" and overlaps == 0:
            issues.append("missing_complete_settle_or_real_pending_movement_overlap")
        if window["metrics"]["sample_count"] < 30:
            issues.append("insufficient_pagination_interval_coverage")
    scheduler = scheduler_evidence(report, baseline, movement, issues, failures)
    if not baseline["anchors_valid"]:
        failures.append("anchor_error_or_missing_anchor")
    if clocks["arrival"]["all"]["stalls_at_least_100_ms"]:
        failures.append("all_active_arrival_stall_at_least_100ms")
    for clock in ("arrival", "display"):
        if clocks[clock]["movement"]["stalls_at_least_100_ms"]:
            failures.append("movement_" + clock + "_stall_at_least_100ms")
    display = clocks["display"]["movement"]
    if display["sample_count"] and (display["p95_ms"] > 16.7 or display["over_33_3_ms_fraction"] > .01):
        failures.append("movement_display_cadence_threshold")
    issues, failures = sorted(set(issues)), sorted(set(failures))
    return {
        "policy": "interaction-priority", "contract_version": "movement-idle-v1",
        "experienceGateStatus": "insufficient-evidence" if issues else "fail" if failures else "pass",
        "renderFPSStatus": "unsupported", "evaluable": not issues,
        "interval_classification": "whole closed interval touching any observed gesture window is movement; no clipping; clocks classified independently",
        "movement_windows": [{"start_seconds": start, "end_seconds": end} for start, end in movement],
        "clocks": clocks, "pagination_movement_overlap": coverage, "scheduler": scheduler,
        "insufficient_evidence": issues, "failures": failures,
        "thresholds": {"movement_display_p95_ms": 16.7, "movement_display_over_33_3_fraction": .01,
                       "all_active_arrival_stall_ms": 100, "movement_arrival_and_display_stall_ms": 100,
                       "idle_display_stall_is_observation_only": True, "anchor_error_pt": 1,
                       "continuous_idle_ms": 120, "maximum_inserted_display_rows_per_prepend_batch": 2},
        "notes": "Idle statistics and unfinished ages remain public for review. Idle display long intervals are observations, not a standalone failure; all-active arrival and movement in either clock still have the 100ms hard gate. This independent scrolling/scheduling result never replaces acceptanceGateStatus, measures GPU FPS, or establishes that all waiting delays are acceptable. Tick separation requires source/native evidence; these events contain no frame/tick identifier.",
    }


def evaluate(report, gate_policy="legacy-arrival", experience_policy=None):
    if gate_policy not in GATE_POLICIES:
        raise ValueError("unknown gate policy")
    if experience_policy is not None and experience_policy not in EXPERIENCE_POLICIES:
        raise ValueError("unknown experience policy")
    schema = report.get("schemaVersion")
    if schema not in {2, 3}:
        raise ValueError("schemaVersion=2 or 3 required; schema1 cannot establish pagination/scroll overlap")
    measurement_start = report.get("measurementStart", "viewport")
    if measurement_start not in {"viewport", "first_user_drag"}:
        raise ValueError("unknown measurementStart")
    if ("initialPipelineInFlight" in report and type(report["initialPipelineInFlight"]) is not bool
            or measurement_start == "first_user_drag" and "initialPipelineInFlight" not in report):
        raise ValueError("explicit initialPipelineInFlight boolean required for first-user-drag capture")
    required_flags = ("sampleLimitReached", "eventLimitReached", "unfinishedPagination", "unfinishedPreparation",
                      "unfinishedCommit", "unfinishedGesture", "lifecycleMismatch")
    if any(type(report.get(key)) is not bool for key in required_flags):
        raise ValueError("explicit lifecycle/limit booleans required")
    for key in ("durationSeconds", "trailingActiveUnobservedMs"):
        if not finite_number(report.get(key)) or report[key] < 0:
            raise ValueError(f"invalid/missing {key}")
    samples = report.get("samples", [])
    events = report.get("events", [])
    numeric_fields = ("callbackArrivalIntervalMs", "expectedDurationMs",
                      "callbackDeadlineLatenessMs", "elapsedSeconds")
    if not isinstance(samples, list) or not isinstance(events, list):
        raise ValueError("samples/events must be arrays")
    missing_display_intervals = 0
    nonpositive_display_intervals = 0
    misplaced_first_callbacks = False
    for index, sample in enumerate(samples):
        if sample.get("phase") not in {"scroll", "waiting", "prepare", "commit", "settle"}:
            raise ValueError("unknown phase")
        for key in numeric_fields:
            if not finite_number(sample.get(key)) or sample[key] < 0:
                raise ValueError(f"invalid numeric sample: {key}")
        if sample["expectedDurationMs"] <= 0:
            raise ValueError("expectedDurationMs must be positive")
        if any(type(sample.get(key)) is not bool for key in ("isScrolling", "isHistoryLoading", "activationClipped")):
            raise ValueError("independent scroll/pipeline flags required")
        first_callback = sample.get("isFirstCallback", False)
        if type(first_callback) is not bool:
            raise ValueError("isFirstCallback must be a boolean")
        display_interval = sample.get("displayTimestampIntervalMs")
        if first_callback:
            if display_interval is not None:
                raise ValueError("first callback has no comparable previous display timestamp")
            if index != 0:
                misplaced_first_callbacks = True
        elif display_interval is None and gate_policy == "display-cadence":
            # Missing evidence cannot be silently excluded to make display cadence look healthy.
            missing_display_intervals += 1
        elif not finite_number(display_interval) or display_interval < 0:
            raise ValueError("invalid/missing comparable display timestamp")
        elif display_interval == 0 and gate_policy == "display-cadence":
            # A comparable display timestamp must advance; zero is not a healthy frame.
            nonpositive_display_intervals += 1
    if measurement_start == "first_user_drag" and samples:
        if samples[0].get("isFirstCallback") is not True or any(sample.get("isFirstCallback") is not False for sample in samples[1:]):
            raise ValueError("first-user-drag capture must retain exactly its first callback")
    for event in events:
        if not finite_number(event.get("elapsedSeconds")) or event["elapsedSeconds"] < 0:
            raise ValueError("event elapsedSeconds required")
    if schema == 2:
        commits, windows, lifecycle_valid, lifecycle_detail = pagination_v2(report, samples, events)
    else:
        commits, windows, lifecycle_valid, lifecycle_detail = pagination_v3(report, samples, events)
    anchors_valid = bool(commits) and all(
        event.get("anchorValid") is True and finite_number(event.get("anchorErrorPt"))
        and abs(event["anchorErrorPt"]) <= 1 for event in commits
    )
    summary = frame_summary(samples)
    by_phase = {phase: frame_summary([sample for sample in samples if sample["phase"] == phase])
                for phase in ("scroll", "waiting", "prepare", "commit", "settle")}
    pipeline_samples = [sample for sample in samples
                        if any(sample["elapsedSeconds"] >= window["start_seconds"]
                               and sample["elapsedSeconds"] - sample["callbackArrivalIntervalMs"] / 1000 <= window["settle_end_seconds"]
                               for window in windows)]
    loading = frame_summary(pipeline_samples)
    incomplete = [key for key in required_flags if report[key]]
    incomplete.extend(lifecycle_detail.get("incomplete_operations", []))
    policy_incomplete = []
    if gate_policy == "display-cadence":
        if schema != 3:
            policy_incomplete.append("display_cadence_requires_schema3")
        if missing_display_intervals:
            policy_incomplete.append("missing_comparable_display_intervals")
        if nonpositive_display_intervals:
            policy_incomplete.append("nonpositive_comparable_display_intervals")
        if misplaced_first_callbacks:
            policy_incomplete.append("misplaced_first_callback")
    if report.get("initialPipelineInFlight", False):
        incomplete.append("initialPipelineInFlight")
    tail_ms = report["trailingActiveUnobservedMs"]
    if tail_ms > 33.3:
        incomplete.append("unobserved_tail_over_33_3_ms")
    base_evaluable = (len(samples) >= 120 and sum(sample["isScrolling"] for sample in samples) >= 30
                 and len(pipeline_samples) >= 30 and any(commit["insertedRows"] > 0 for commit in commits)
                 and lifecycle_valid and not incomplete
                 and (schema == 3 or len(windows) == len(commits)) and all(window["complete_settle"]
                     and (window.get("outcome") == "empty" or window["scroll_pipeline_overlap_samples"] > 0)
                     for window in windows))
    evaluable = base_evaluable and not policy_incomplete
    incomplete.extend(policy_incomplete)
    # callbackGateStatus always retains the historical arrival interpretation.
    # The explicitly selected policy has its own acceptanceGateStatus.
    legacy_evaluable = base_evaluable and not missing_display_intervals
    def pacing_pass(policy):
        return (gate_pass(summary, policy) and gate_pass(loading, policy)
                and all(gate_pass(window["metrics"], policy) and all(gate_pass(commit["metrics"], policy)
                    for commit in window.get("commits", [])) for window in windows) and tail_ms < 100)
    legacy_status = ("pass" if legacy_evaluable and pacing_pass("legacy-arrival") and anchors_valid
                     else "fail" if legacy_evaluable else "insufficient-evidence")
    acceptance_status = ("pass" if evaluable and pacing_pass(gate_policy) and anchors_valid
                         else "fail" if evaluable else "insufficient-evidence")
    result = {
        "measurement": "CADisplayLink main-thread/display callback pacing; not rendered FPS",
        "renderFPSStatus": "unsupported",
        "acceptance_policy": gate_policy,
        "acceptance_policy_details": {
            "p95_and_over_33_3_fraction_source": ("comparable display timestamp intervals"
                if gate_policy == "display-cadence" else "all callback arrival intervals"),
            "hard_stall_sources": (["all callback arrival intervals", "all comparable display timestamp intervals"]
                if gate_policy == "display-cadence" else ["all callback arrival intervals"]),
            "first_callback": "unavailable first display interval excluded; actual first arrival interval retained",
            "missing_comparable_display_intervals": missing_display_intervals,
            "nonpositive_comparable_display_intervals": nonpositive_display_intervals,
            "gpu_or_rendered_fps_claim": False,
        },
        "schema_version": schema,
        "measurement_start": measurement_start,
        "loading_semantics": ("accepted-pagination operation through terminal and final required presentation; not network I/O"
                              if schema == 3 else "legacy first presentation commit; cannot establish multi-commit batch completion"),
        "legacy_schema_limitation": (None if schema == 3 else
            "Schema2 ends each pagination window at its first commit. It cannot prove completion of internally batched later commits and is not final schema3 acceptance evidence."),
        "operation_evidence": lifecycle_detail,
        "callbackGateStatus": legacy_status,
        "callback_gate_policy": "legacy-arrival",
        "acceptanceGateStatus": acceptance_status,
        "evaluable": evaluable,
        "percentile_method": "nearest rank, ceil(n * p), all active samples, no outlier removal",
        "callback_rate_method": "1000 / mean(full callback arrival intervals); activation-clipped intervals excluded; not FPS",
        "thresholds": {"p95_ms": 16.7, "over_33_3_ms_fraction": 0.01, "stall_ms": 100, "anchor_error_pt": 1},
        "maximumFramesPerSecond": report.get("maximumFramesPerSecond"),
        "active": summary, "by_phase": by_phase,
        "loading_and_settle": loading, "pagination_windows": windows,
        "trailing_active_unobserved_ms": tail_ms,
        "inserted_rows": sum(event["insertedRows"] for event in commits),
        "completed_page_commits": len(commits), "lifecycle_valid": lifecycle_valid,
        "incomplete_or_truncated": incomplete,
        "anchors_valid": anchors_valid, "anchor_errors_pt": [event.get("anchorErrorPt") for event in commits],
        "end_reason": report.get("endReason"),
        "notes": ("Idle excluded at collection; active long pauses retained. Pagination windows include first accepted call through operation complete+1s; every commit retains its anchor and settle evidence. Fixed 60Hz callback target; actual nominal duration reported separately."
                  if schema == 3 else "Legacy schema2: first commit+1s only; later commits in the same accepted batch may be outside its pagination window. All raw samples remain unchanged. Fixed 60Hz callback target; actual nominal duration reported separately."),
    }
    if experience_policy is not None:
        experience = interaction_experience(report, result)
        result.update(experience_policy=experience_policy,
                      experienceGateStatus=experience["experienceGateStatus"], experience=experience)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--gate-policy", choices=GATE_POLICIES, default="legacy-arrival",
                        help="Default preserves legacy arrival gating; display-cadence is explicit and requires schema3 evidence.")
    parser.add_argument("--experience-policy", choices=EXPERIENCE_POLICIES,
                        help="Add an independent interaction-priority report; legacy acceptance fields are unchanged.")
    args = parser.parse_args()
    result = evaluate(json.loads(args.report.read_text()), gate_policy=args.gate_policy,
                      experience_policy=args.experience_policy)
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    print(text, end="")


if __name__ == "__main__":
    main()
