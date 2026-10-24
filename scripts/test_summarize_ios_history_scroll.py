import unittest

from summarize_ios_history_scroll import evaluate, frame_summary, gate_pass, percentile


def report():
    value = {
        "schemaVersion": 2, "maximumFramesPerSecond": 60, "durationSeconds": 2.6,
        "sampleLimitReached": False, "eventLimitReached": False, "unfinishedPagination": False,
        "unfinishedPreparation": False, "unfinishedCommit": False, "unfinishedGesture": False,
        "lifecycleMismatch": False, "trailingActiveUnobservedMs": 10,
        "samples": [{"elapsedSeconds": i / 60, "callbackArrivalIntervalMs": 16.666,
                     "displayTimestampIntervalMs": 16.666, "expectedDurationMs": 16.666,
                     "callbackDeadlineLatenessMs": 0, "phase": "scroll" if i < 90 else "settle",
                     "isScrolling": i < 90, "isHistoryLoading": 30 <= i < 90,
                     "loadOrdinal": 1 if 30 <= i < 90 else None, "activationClipped": i == 0}
                    for i in range(156)],
        "events": [{"phase": "history_load_requested", "loadOrdinal": 1, "elapsedSeconds": .5},
                   {"phase": "history_commit_begin", "loadOrdinal": 1, "elapsedSeconds": 1.45},
                   {"phase": "history_commit_end", "loadOrdinal": 1, "elapsedSeconds": 1.5,
                    "insertedRows": 20, "anchorValid": True, "anchorErrorPt": 0.2}],
    }
    return value


def event(value, phase, occurrence=0):
    return [item for item in value["events"] if item["phase"] == phase][occurrence]


def add_event(value, item):
    value["events"].append(item)
    value["events"].sort(key=lambda item: item["elapsedSeconds"])


def report_v3():
    value = report()
    value.update(schemaVersion=3, acceptedCallCount=1, coalescedAcceptedCallCount=0,
                 rejectedCallCount=0, approachCallCount=0, diagnosticAggregateCapacity=128,
                 diagnosticAggregateLimitReached=False, droppedApproachAggregateCallCount=0,
                 droppedRejectedAggregateCallCount=0, diagnosticAggregates=[],
                 staleOperationFinishCount=0, operationTrackingMode="owned_admission")
    value["events"] = [
        {"phase": "history_load_requested", "loadOrdinal": 1, "elapsedSeconds": .5,
         "acceptedCallOrdinal": 1, "coalesced": False},
        {"phase": "history_operation_required", "loadOrdinal": 1, "elapsedSeconds": .6, "projectionEpoch": 2},
        {"phase": "history_operation_issued", "loadOrdinal": 1, "elapsedSeconds": .61, "projectionEpoch": 2},
        {"phase": "history_commit_begin", "loadOrdinal": 1, "elapsedSeconds": 1.4, "insertedRows": 20},
        {"phase": "history_commit_end", "loadOrdinal": 1, "elapsedSeconds": 1.45,
         "insertedRows": 20, "anchorValid": True, "anchorErrorPt": .2},
        {"phase": "history_operation_committed", "loadOrdinal": 1, "elapsedSeconds": 1.455, "projectionEpoch": 2},
        {"phase": "history_operation_presented", "loadOrdinal": 1, "elapsedSeconds": 1.46, "projectionEpoch": 2},
        {"phase": "history_operation_terminal", "loadOrdinal": 1, "elapsedSeconds": 1.48},
        {"phase": "history_operation_complete", "loadOrdinal": 1, "elapsedSeconds": 1.5, "outcome": "presented"},
    ]
    return value


def report_v3_two_commits():
    value = report_v3()
    event(value, "history_commit_begin")["elapsedSeconds"] = .75
    event(value, "history_commit_end")["elapsedSeconds"] = .8
    event(value, "history_operation_committed")["elapsedSeconds"] = .805
    event(value, "history_operation_presented")["elapsedSeconds"] = .81
    value["events"] += [
        {"phase": "history_operation_required", "loadOrdinal": 1, "elapsedSeconds": .9, "projectionEpoch": 3},
        {"phase": "history_operation_issued", "loadOrdinal": 1, "elapsedSeconds": .91, "projectionEpoch": 3},
        {"phase": "history_commit_begin", "loadOrdinal": 1, "elapsedSeconds": 1.2, "insertedRows": 18},
        {"phase": "history_commit_end", "loadOrdinal": 1, "elapsedSeconds": 1.3,
         "insertedRows": 18, "anchorValid": True, "anchorErrorPt": .1},
        {"phase": "history_operation_committed", "loadOrdinal": 1, "elapsedSeconds": 1.305, "projectionEpoch": 3},
        {"phase": "history_operation_presented", "loadOrdinal": 1, "elapsedSeconds": 1.31, "projectionEpoch": 3},
    ]
    value["events"].sort(key=lambda item: item["elapsedSeconds"])
    return value


class OperationSummaryTests(unittest.TestCase):
    def test_v3_complete_operation_passes(self):
        result = evaluate(report_v3())
        self.assertEqual(result["callbackGateStatus"], "pass")
        self.assertEqual(result["schema_version"], 3)
        self.assertEqual(result["renderFPSStatus"], "unsupported")
        self.assertIsNone(result["legacy_schema_limitation"])
        self.assertEqual(result["operation_evidence"]["accepted_calls"], 1)

    def test_v3_multiple_commits_are_one_complete_pipeline(self):
        result = evaluate(report_v3_two_commits())
        self.assertEqual(result["callbackGateStatus"], "pass")
        self.assertEqual(result["completed_page_commits"], 2)
        self.assertEqual(result["inserted_rows"], 38)
        self.assertEqual(len(result["pagination_windows"]), 1)
        self.assertEqual(result["pagination_windows"][0]["commit_count"], 2)
        self.assertEqual(len(result["pagination_windows"][0]["commits"]), 2)

    def test_v3_first_commit_cannot_clear_pipeline_before_second(self):
        value = report_v3_two_commits()
        for sample in value["samples"]:
            if .8 < sample["elapsedSeconds"] < 1.3:
                sample.update(isHistoryLoading=False, loadOrdinal=None)
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_first_commit_cannot_complete_operation_early(self):
        value = report_v3_two_commits()
        add_event(value, {"phase": "history_operation_complete", "loadOrdinal": 1,
                          "elapsedSeconds": .82, "outcome": "presented"})
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_terminal_alone_does_not_end_operation(self):
        value = report_v3_two_commits()
        add_event(value, {"phase": "history_operation_terminal", "loadOrdinal": 1,
                          "elapsedSeconds": .55})
        self.assertEqual(evaluate(value)["callbackGateStatus"], "pass")

    def test_v3_presented_may_precede_terminal(self):
        value = report_v3()
        self.assertLess(event(value, "history_operation_presented")["elapsedSeconds"],
                        event(value, "history_operation_terminal")["elapsedSeconds"])
        self.assertEqual(evaluate(value)["callbackGateStatus"], "pass")

    def test_v3_latest_required_epoch_needs_actual_ack(self):
        value = report_v3_two_commits()
        event(value, "history_operation_presented", 1)["projectionEpoch"] = 2
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")
        event(value, "history_operation_presented", 1)["projectionEpoch"] = 999
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_presentation_ack_before_actual_commit_end_is_invalid(self):
        value = report_v3_two_commits()
        event(value, "history_operation_presented", 1)["elapsedSeconds"] = 1.25
        value["events"].sort(key=lambda item: item["elapsedSeconds"])
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_old_operation_ack_is_ignored_and_cannot_complete_current(self):
        value = report_v3_two_commits()
        event(value, "history_operation_presented", 1).update(phase="history_operation_presented_ignored", loadOrdinal=99)
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")
        value = report_v3()
        add_event(value, {"phase": "history_operation_finish_ignored", "loadOrdinal": 99,
                          "elapsedSeconds": .65, "outcome": "presented"})
        value["staleOperationFinishCount"] = 1
        self.assertEqual(evaluate(value)["callbackGateStatus"], "pass")

    def test_v3_unfinished_second_page_cannot_pass(self):
        value = report_v3_two_commits()
        value["events"] = [item for item in value["events"] if item["elapsedSeconds"] <= 1.2]
        value.update(unfinishedPagination=True, unfinishedCommit=True)
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_coalesced_accepts_are_counted_and_invalidate_old_terminal(self):
        value = report_v3_two_commits()
        value.update(acceptedCallCount=2, coalescedAcceptedCallCount=1,
                     operationTrackingMode="observational_coalescing")
        add_event(value, {"phase": "history_operation_terminal", "loadOrdinal": 1, "elapsedSeconds": .85})
        add_event(value, {"phase": "history_load_requested", "loadOrdinal": 1, "elapsedSeconds": .86,
                          "acceptedCallOrdinal": 2, "coalesced": True})
        result = evaluate(value)
        self.assertEqual(result["callbackGateStatus"], "pass")
        self.assertEqual(result["operation_evidence"]["accepted_calls"], 2)
        self.assertEqual(result["operation_evidence"]["coalesced_accepted_calls"], 1)
        value["events"] = [event for event in value["events"]
                           if not (event["phase"] == "history_operation_terminal" and event["elapsedSeconds"] > 1)]
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_owned_admission_cannot_silently_coalesce_accepts(self):
        value = report_v3()
        value.update(acceptedCallCount=2, coalescedAcceptedCallCount=1)
        add_event(value, {"phase": "history_load_requested", "loadOrdinal": 1,
                          "elapsedSeconds": .55, "acceptedCallOrdinal": 2, "coalesced": True})
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_rejected_calls_have_separate_count(self):
        value = report_v3()
        value["rejectedCallCount"] = 1
        value["diagnosticAggregates"] = [{"phase": "history_admission_rejected", "loadOrdinal": 1,
            "reason": "presentation_pending", "firstElapsedSeconds": .55, "lastElapsedSeconds": .55, "count": 1}]
        result = evaluate(value)
        self.assertEqual(result["callbackGateStatus"], "pass")
        self.assertEqual(result["operation_evidence"]["rejected_calls"], 1)
        self.assertEqual(result["operation_evidence"]["accepted_calls"], 1)
        value["acceptedCallCount"] = 2
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_each_commit_anchor_and_settle_must_be_complete(self):
        value = report_v3_two_commits()
        event(value, "history_commit_end")["anchorValid"] = False
        self.assertEqual(evaluate(value)["callbackGateStatus"], "fail")
        value = report_v3_two_commits()
        value["samples"] = value["samples"][:135]
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_second_page_stall_is_retained(self):
        value = report_v3_two_commits()
        value["samples"][70]["callbackArrivalIntervalMs"] = 130
        result = evaluate(value)
        self.assertEqual(result["callbackGateStatus"], "fail")
        self.assertEqual(result["loading_and_settle"]["stalls_at_least_100_ms"], 1)

    def test_v3_complete_requires_terminal_and_matching_outcome(self):
        for outcome in ("failed", "cancelled", "detached", "empty", "unknown"):
            value = report_v3()
            value["events"][-1]["outcome"] = outcome
            self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence", outcome)
        value = report_v3()
        value["events"] = [event for event in value["events"] if event["phase"] != "history_operation_terminal"]
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_counts_and_mode_are_required(self):
        for key in ("acceptedCallCount", "coalescedAcceptedCallCount", "rejectedCallCount",
                    "staleOperationFinishCount", "operationTrackingMode"):
            value = report_v3()
            value.pop(key)
            with self.assertRaises(ValueError, msg=key):
                evaluate(value)

    def test_v3_fast_empty_does_not_replace_or_break_positive_evidence(self):
        value = report_v3()
        value.update(acceptedCallCount=2, durationSeconds=3.8)
        value["samples"] += [dict(value["samples"][-1], elapsedSeconds=i / 60,
                                  isHistoryLoading=False, loadOrdinal=None) for i in range(156, 228)]
        value["events"] += [
            {"phase": "history_load_requested", "loadOrdinal": 2, "elapsedSeconds": 2.6,
             "acceptedCallOrdinal": 2, "coalesced": False},
            {"phase": "history_operation_terminal", "loadOrdinal": 2, "elapsedSeconds": 2.6},
            {"phase": "history_operation_complete", "loadOrdinal": 2, "elapsedSeconds": 2.6,
             "outcome": "empty"},
        ]
        result = evaluate(value)
        self.assertEqual(result["callbackGateStatus"], "pass")
        self.assertEqual(result["completed_page_commits"], 1)
        self.assertEqual(result["pagination_windows"][1]["scroll_pipeline_overlap_samples"], 0)
        value["samples"][170]["callbackArrivalIntervalMs"] = 130
        result = evaluate(value)
        self.assertEqual(result["callbackGateStatus"], "fail")
        self.assertEqual(result["pagination_windows"][1]["metrics"]["stalls_at_least_100_ms"], 1)

    def test_v3_empty_only_is_never_positive_pagination_evidence(self):
        value = report_v3()
        value["events"] = [value["events"][0], value["events"][-2], value["events"][-1]]
        value["events"][-1]["outcome"] = "empty"
        result = evaluate(value)
        self.assertTrue(result["lifecycle_valid"])
        self.assertEqual(result["callbackGateStatus"], "insufficient-evidence")
        self.assertEqual(result["inserted_rows"], 0)

    def test_v3_presented_before_commit_begin_cannot_pass(self):
        value = report_v3()
        event(value, "history_operation_presented")["elapsedSeconds"] = .65
        value["events"].sort(key=lambda item: item["elapsedSeconds"])
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_committed_and_presented_before_begin_cannot_satisfy_final_ack(self):
        value = report_v3()
        event(value, "history_operation_committed")["elapsedSeconds"] = .64
        event(value, "history_operation_presented")["elapsedSeconds"] = .65
        value["events"].sort(key=lambda item: item["elapsedSeconds"])
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_second_epoch_ack_before_second_commit_cannot_pass(self):
        value = report_v3_two_commits()
        event(value, "history_operation_committed", 1)["elapsedSeconds"] = 1
        event(value, "history_operation_presented", 1)["elapsedSeconds"] = 1.01
        value["events"].sort(key=lambda item: item["elapsedSeconds"])
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_real_higher_issued_and_committed_epoch_may_satisfy_required(self):
        value = report_v3()
        for phase in ("history_operation_issued", "history_operation_committed", "history_operation_presented"):
            event(value, phase)["projectionEpoch"] = 3
        result = evaluate(value)
        self.assertEqual(result["callbackGateStatus"], "pass")
        self.assertEqual(result["pagination_windows"][0]["required_projection_epoch"], 2)
        self.assertEqual(result["pagination_windows"][0]["issued_projection_epochs"], [3])
        self.assertEqual(result["pagination_windows"][0]["committed_projection_epochs"], [3])

    def test_v3_fictitious_future_epoch_cannot_pass(self):
        value = report_v3()
        for phase in ("history_operation_committed", "history_operation_presented"):
            event(value, phase)["projectionEpoch"] = 999
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_actual_committed_evidence_is_mandatory(self):
        value = report_v3()
        value["events"] = [item for item in value["events"] if item["phase"] != "history_operation_committed"]
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_committed_during_pending_snapshot_is_invalid(self):
        value = report_v3()
        event(value, "history_operation_committed")["elapsedSeconds"] = 1.42
        value["events"].sort(key=lambda item: item["elapsedSeconds"])
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_first_ack_then_terminal_then_final_ack_is_valid(self):
        value = report_v3_two_commits()
        event(value, "history_operation_terminal")["elapsedSeconds"] = .85
        value["events"].sort(key=lambda item: item["elapsedSeconds"])
        self.assertEqual(evaluate(value)["callbackGateStatus"], "pass")

    def test_v3_high_frequency_rejects_and_approaches_preserve_lifecycle_capacity(self):
        value = report_v3()
        value.update(rejectedCallCount=100000, approachCallCount=100000)
        value["diagnosticAggregates"] = [
            {"phase": "history_admission_rejected", "loadOrdinal": 1, "reason": "presentation_pending",
             "firstElapsedSeconds": .55, "lastElapsedSeconds": 1.49, "count": 100000},
            {"phase": "history_approach", "loadOrdinal": 1,
             "firstElapsedSeconds": .55, "lastElapsedSeconds": 1.49, "count": 100000},
        ]
        result = evaluate(value)
        self.assertEqual(result["callbackGateStatus"], "pass")
        self.assertEqual(len(value["events"]), 9)
        aggregation = result["operation_evidence"]["diagnostic_aggregation"]
        self.assertEqual(aggregation["rejected_calls"], 100000)
        self.assertEqual(aggregation["approach_calls"], 100000)
        self.assertEqual(aggregation["aggregate_keys"], 2)

    def test_v3_any_aggregate_drop_is_insufficient(self):
        value = report_v3()
        value.update(rejectedCallCount=1, droppedRejectedAggregateCallCount=1,
                     diagnosticAggregateLimitReached=True)
        result = evaluate(value)
        self.assertEqual(result["callbackGateStatus"], "insufficient-evidence")
        self.assertIn("diagnostic_aggregate_limit_reached", result["incomplete_or_truncated"])

    def test_v3_aggregate_exact_counts_and_key_uniqueness_are_checked(self):
        value = report_v3()
        aggregate = {"phase": "history_approach", "loadOrdinal": 1,
                     "firstElapsedSeconds": .55, "lastElapsedSeconds": 1.49, "count": 200}
        value["diagnosticAggregates"] = [aggregate]
        value["approachCallCount"] = 201
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")
        value["approachCallCount"] = 400
        value["diagnosticAggregates"].append(dict(aggregate))
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_v3_aggregate_times_and_operation_attribution_are_checked(self):
        for first, last, ordinal in ((.4, 1.49, 1), (.55, 1.51, 1), (.55, 1.49, 2), (.55, 1.49, None)):
            value = report_v3()
            value["approachCallCount"] = 1
            value["diagnosticAggregates"] = [{"phase": "history_approach", "loadOrdinal": ordinal,
                "firstElapsedSeconds": first, "lastElapsedSeconds": last, "count": 1}]
            self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")
        for first, last in ((1, .5), (-1, .5), (.5, 9)):
            value = report_v3()
            value["approachCallCount"] = 1
            value["diagnosticAggregates"] = [{"phase": "history_approach", "loadOrdinal": 1,
                "firstElapsedSeconds": first, "lastElapsedSeconds": last, "count": 1}]
            with self.assertRaises(ValueError):
                evaluate(value)

    def test_v3_aggregate_reasons_are_canonical_and_all_fields_required(self):
        value = report_v3()
        value["rejectedCallCount"] = 1
        value["diagnosticAggregates"] = [{"phase": "history_admission_rejected", "loadOrdinal": 1,
            "reason": "domain loading", "firstElapsedSeconds": .55, "lastElapsedSeconds": .55, "count": 1}]
        with self.assertRaises(ValueError):
            evaluate(value)
        for key in ("approachCallCount", "diagnosticAggregateCapacity", "diagnosticAggregateLimitReached",
                    "droppedApproachAggregateCallCount", "droppedRejectedAggregateCallCount", "diagnosticAggregates"):
            value = report_v3()
            value.pop(key)
            with self.assertRaises(ValueError, msg=key):
                evaluate(value)

    def test_v3_aggregate_capacity_is_separate_and_enforced(self):
        value = report_v3()
        value["approachCallCount"] = 129
        value["diagnosticAggregates"] = [
            {"phase": "history_approach", "loadOrdinal": 1,
             "firstElapsedSeconds": .55, "lastElapsedSeconds": .55, "count": 1}
            for _ in range(129)]
        with self.assertRaises(ValueError):
            evaluate(value)

    def test_v3_aggregate_without_pending_operation_and_canonical_reasons(self):
        value = report_v3()
        value["approachCallCount"] = 2
        value["diagnosticAggregates"] = [{"phase": "history_approach", "loadOrdinal": None,
            "firstElapsedSeconds": .1, "lastElapsedSeconds": .3, "count": 2}]
        for reason in ("presentation_pending", "domain_loading", "no_more_history", "projection_pending", "other"):
            value["rejectedCallCount"] += 1
            value["diagnosticAggregates"].append({"phase": "history_admission_rejected", "loadOrdinal": 1,
                "reason": reason, "firstElapsedSeconds": .55, "lastElapsedSeconds": .55, "count": 1})
        self.assertEqual(evaluate(value)["callbackGateStatus"], "pass")

    def test_schema2_output_is_explicitly_legacy(self):
        result = evaluate(report())
        self.assertIn("first commit", result["legacy_schema_limitation"])
        self.assertEqual(result["callbackGateStatus"], "pass")


class SummaryTests(unittest.TestCase):
    def test_first_drag_cannot_hide_initial_pipeline(self):
        value = report()
        value.update(measurementStart="first_user_drag", initialPipelineInFlight=True)
        for index, sample in enumerate(value["samples"]):
            sample["isFirstCallback"] = index == 0
        value["samples"][0]["displayTimestampIntervalMs"] = None
        result = evaluate(value)
        self.assertEqual(result["callbackGateStatus"], "insufficient-evidence")
        self.assertIn("initialPipelineInFlight", result["incomplete_or_truncated"])
        value["initialPipelineInFlight"] = False
        self.assertEqual(evaluate(value)["callbackGateStatus"], "pass")
        value.pop("initialPipelineInFlight")
        with self.assertRaises(ValueError):
            evaluate(value)

    def test_first_callback_real_stall_is_retained(self):
        value = report()
        value["samples"][0].update(isFirstCallback=True, displayTimestampIntervalMs=None,
                                   callbackArrivalIntervalMs=150, activationClipped=True)
        result = evaluate(value)
        self.assertEqual(result["callbackGateStatus"], "fail")
        self.assertEqual(result["active"]["stalls_at_least_100_ms"], 1)
        self.assertEqual(result["active"]["first_callback_samples"], 1)
        self.assertEqual(result["active"]["comparable_display_timestamp_samples"], 155)

    def test_first_callback_timestamp_cannot_be_fabricated(self):
        value = report()
        value["samples"][0]["isFirstCallback"] = True
        with self.assertRaises(ValueError):
            evaluate(value)

    def test_nearest_rank(self):
        self.assertEqual(percentile(list(range(1, 101)), .95), 95)
        self.assertIsNone(percentile([], .95))

    def test_pass_is_callback_only(self):
        result = evaluate(report())
        self.assertEqual(result["callbackGateStatus"], "pass")
        self.assertEqual(result["renderFPSStatus"], "unsupported")
        self.assertEqual(result["inserted_rows"], 20)
        self.assertEqual(result["active"]["p95_display_timestamp_interval_ms"], 16.666)
        self.assertAlmostEqual(result["active"]["effective_callback_rate_hz"], 60, places=2)
        self.assertGreater(result["pagination_windows"][0]["scroll_pipeline_overlap_samples"], 0)

    def test_approach_without_insertion_never_passes(self):
        value = report()
        value["events"] = [{"phase": "history_approach", "rowCount": 20, "elapsedSeconds": .5}]
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_invalid_or_missing_anchor_not_zero(self):
        for anchor in ({"anchorValid": False}, {"anchorValid": True, "anchorErrorPt": None},
                       {"anchorValid": True, "anchorErrorPt": 2}):
            value = report()
            value["events"][-1].update(anchor)
            self.assertEqual(evaluate(value)["callbackGateStatus"], "fail")

    def test_100ms_loading_pause_is_never_filtered(self):
        value = report()
        value["samples"][95].update(callbackArrivalIntervalMs=100, phase="prepare")
        result = evaluate(value)
        self.assertEqual(result["callbackGateStatus"], "fail")
        self.assertEqual(result["active"]["stalls_at_least_100_ms"], 1)

    def test_33ms_rate_and_p95(self):
        value = report()
        for item in value["samples"][:2]:
            item["callbackArrivalIntervalMs"] = 34
        self.assertEqual(evaluate(value)["callbackGateStatus"], "fail")
        value = report()
        for item in value["samples"][:8]:
            item["callbackArrivalIntervalMs"] = 17
        self.assertEqual(evaluate(value)["callbackGateStatus"], "fail")

    def test_no_overlap_insufficient_even_with_scroll_and_loading(self):
        value = report()
        for sample in value["samples"]:
            sample["isHistoryLoading"] = False
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_no_malformed_numbers(self):
        for number in (float("nan"), float("inf"), -1, None, True):
            value = report()
            value["samples"][0]["callbackArrivalIntervalMs"] = number
            with self.assertRaises(ValueError):
                evaluate(value)

    def test_all_limits_and_unfinished_lifecycles_block_pass(self):
        for flag in ("sampleLimitReached", "eventLimitReached", "unfinishedPagination", "unfinishedPreparation",
                     "unfinishedCommit", "unfinishedGesture", "lifecycleMismatch"):
            value = report()
            value[flag] = True
            self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence", flag)

    def test_earlier_success_cannot_hide_later_unfinished_page(self):
        value = report()
        value["events"].append({"phase": "history_load_requested", "elapsedSeconds": 2.4, "loadOrdinal": 2})
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_completed_page_cannot_reuse_same_ordinal(self):
        value = report()
        value["events"].append(dict(value["events"][-1]))
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_partial_settle_is_not_complete(self):
        value = report()
        value["durationSeconds"] = 2.4
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_schema1_or_missing_lifecycle_and_tail_rejected(self):
        for missing in ("schemaVersion", "unfinishedPagination", "trailingActiveUnobservedMs"):
            value = report()
            value.pop(missing)
            with self.assertRaises(ValueError):
                evaluate(value)

    def test_tail_stall_cannot_pass(self):
        value = report()
        value["trailingActiveUnobservedMs"] = 100
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_34ms_unobserved_tail_cannot_pass(self):
        value = report()
        value["trailingActiveUnobservedMs"] = 34
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_cross_settle_boundary_stall_is_in_page_gate(self):
        value = report()
        value["samples"][-1].update(elapsedSeconds=2.7, callbackArrivalIntervalMs=150)
        value["durationSeconds"] = 2.8
        # 该回调前驱为2.55，未跨2.5，故不应属于该页。
        self.assertEqual(evaluate(value)["pagination_windows"][0]["metrics"]["stalls_at_least_100_ms"], 0)
        value["samples"][-1]["callbackArrivalIntervalMs"] = 250
        self.assertEqual(evaluate(value)["pagination_windows"][0]["metrics"]["stalls_at_least_100_ms"], 1)

    def test_no_callback_reaching_settle_end_is_insufficient(self):
        value = report()
        value["samples"] = value["samples"][:150]
        self.assertEqual(evaluate(value)["callbackGateStatus"], "insufficient-evidence")

    def test_actual_nominal_refresh_is_preserved(self):
        value = report()
        for sample in value["samples"]:
            sample["expectedDurationMs"] = 8.333
        self.assertEqual(evaluate(value)["active"]["nominal_duration_p50_ms"], 8.333)

    def test_activation_clipped_interval_not_in_callback_rate(self):
        value = report()
        value["samples"][0]["callbackArrivalIntervalMs"] = .01
        self.assertAlmostEqual(evaluate(value)["active"]["effective_callback_rate_hz"], 60, places=2)


class DisplayCadencePolicyTests(unittest.TestCase):
    def jittered_report(self):
        value = report_v3()
        value.update(measurementStart="first_user_drag", initialPipelineInFlight=False)
        previous = None
        for index, sample in enumerate(value["samples"]):
            arrival = index / 60 + (0.0033 if index % 2 else 0)
            sample.update(elapsedSeconds=arrival,
                          callbackArrivalIntervalMs=(arrival - previous) * 1000 if previous is not None else 2,
                          isFirstCallback=index == 0,
                          displayTimestampIntervalMs=None if index == 0 else 1000 / 60,
                          isHistoryLoading=.5 <= arrival < 1.5,
                          loadOrdinal=1 if .5 <= arrival < 1.5 else None,
                          isScrolling=arrival < 1.5,
                          phase="scroll" if arrival < 1.5 else "settle")
            previous = arrival
        return value

    def selected(self, value):
        return evaluate(value, gate_policy="display-cadence")

    def test_stable_display_with_arrival_jitter_uses_explicit_policy(self):
        value = self.jittered_report()
        legacy, selected = evaluate(value), self.selected(value)
        self.assertEqual(legacy["acceptance_policy"], "legacy-arrival")
        self.assertEqual(legacy["callbackGateStatus"], "fail")
        self.assertEqual(legacy["acceptanceGateStatus"], "fail")
        self.assertEqual(selected["callbackGateStatus"], "fail", "原arrival结果不可被新策略改写")
        self.assertEqual(selected["acceptanceGateStatus"], "pass")
        self.assertEqual(selected["acceptance_policy"], "display-cadence")
        self.assertEqual(selected["renderFPSStatus"], "unsupported")
        self.assertEqual(selected["thresholds"], legacy["thresholds"])
        self.assertEqual(selected["active"], legacy["active"], "策略切换不能改两套统计")
        self.assertGreater(selected["active"]["arrival_statistics"]["p95_ms"], 16.7)
        self.assertLessEqual(selected["active"]["display_timestamp_statistics"]["p95_ms"], 16.7)

    def test_arrival_100ms_is_hard_failure_even_when_display_is_stable(self):
        for index in (0, 50, 95):
            value = self.jittered_report()
            value["samples"][index]["callbackArrivalIntervalMs"] = 100
            result = self.selected(value)
            self.assertEqual(result["acceptanceGateStatus"], "fail")
            self.assertEqual(result["active"]["stalls_at_least_100_ms"], 1)

    def test_display_stall_cannot_hide_below_p95_or_one_percent(self):
        sample = report_v3()["samples"][1]
        samples = [dict(sample) for _ in range(1000)]
        samples[1]["displayTimestampIntervalMs"] = 100
        summary = frame_summary(samples)
        self.assertEqual(summary["display_timestamp_statistics"]["p95_ms"], 16.666)
        self.assertLess(summary["display_timestamp_statistics"]["over_33_3_ms_fraction"], .01)
        self.assertFalse(gate_pass(summary, "display-cadence"))
        value = self.jittered_report()
        value["samples"][50]["displayTimestampIntervalMs"] = 100
        self.assertEqual(self.selected(value)["acceptanceGateStatus"], "fail")

    def test_display_p95_and_frame_skip_fraction_each_enforce_original_threshold(self):
        value = self.jittered_report()
        for sample in value["samples"][40:52]:
            sample["displayTimestampIntervalMs"] = 17
        self.assertEqual(self.selected(value)["acceptanceGateStatus"], "fail")
        value = self.jittered_report()
        value["samples"][60]["displayTimestampIntervalMs"] = 34
        value["samples"][70]["displayTimestampIntervalMs"] = 34
        result = self.selected(value)
        self.assertLessEqual(result["active"]["display_timestamp_statistics"]["p95_ms"], 16.7)
        self.assertGreater(result["active"]["display_timestamp_statistics"]["over_33_3_ms_fraction"], .01)
        self.assertEqual(result["acceptanceGateStatus"], "fail")

    def test_bad_commit_settle_cannot_be_diluted_by_healthy_active_display(self):
        value = self.jittered_report()
        value["samples"][95]["displayTimestampIntervalMs"] = 34
        result = self.selected(value)
        self.assertLess(result["active"]["display_timestamp_statistics"]["over_33_3_ms_fraction"], .01)
        commit = result["pagination_windows"][0]["commits"][0]
        self.assertGreater(commit["metrics"]["display_timestamp_statistics"]["over_33_3_ms_fraction"], .01)
        self.assertEqual(result["acceptanceGateStatus"], "fail")

    def test_display_ratio_uses_comparable_denominator_and_original_boundaries(self):
        sample = report_v3()["samples"][1]
        samples = [dict(sample, displayTimestampIntervalMs=16.7) for _ in range(101)]
        samples[0].update(isFirstCallback=True, displayTimestampIntervalMs=None)
        samples[1]["displayTimestampIntervalMs"] = 33.3
        summary = frame_summary(samples)
        self.assertEqual(summary["display_timestamp_statistics"]["over_33_3_ms_fraction"], 0)
        self.assertTrue(gate_pass(summary, "display-cadence"))
        samples[1]["displayTimestampIntervalMs"] = 33.31
        summary = frame_summary(samples)
        self.assertEqual(summary["display_timestamp_statistics"]["sample_count"], 100)
        self.assertEqual(summary["display_timestamp_statistics"]["over_33_3_ms_fraction"], .01)
        self.assertTrue(gate_pass(summary, "display-cadence"))
        samples[2]["displayTimestampIntervalMs"] = 33.31
        self.assertFalse(gate_pass(frame_summary(samples), "display-cadence"))

    def test_first_nil_display_is_counted_and_first_arrival_is_retained(self):
        result = self.selected(self.jittered_report())
        self.assertEqual(result["acceptanceGateStatus"], "pass")
        self.assertEqual(result["active"]["first_callback_samples"], 1)
        self.assertEqual(result["active"]["arrival_statistics"]["sample_count"], 156)
        self.assertEqual(result["active"]["display_timestamp_statistics"]["sample_count"], 155)
        value = self.jittered_report()
        value["samples"][0]["callbackArrivalIntervalMs"] = 150
        self.assertEqual(self.selected(value)["acceptanceGateStatus"], "fail")

    def test_missing_nonfirst_display_is_insufficient_instead_of_filtered(self):
        for missing in ("absent", "null"):
            value = self.jittered_report()
            if missing == "absent":
                value["samples"][60].pop("displayTimestampIntervalMs")
            else:
                value["samples"][60]["displayTimestampIntervalMs"] = None
            result = self.selected(value)
            self.assertEqual(result["acceptanceGateStatus"], "insufficient-evidence")
            self.assertIn("missing_comparable_display_intervals", result["incomplete_or_truncated"])
            self.assertEqual(result["acceptance_policy_details"]["missing_comparable_display_intervals"], 1)
            with self.assertRaises(ValueError):
                evaluate(value)

    def test_missing_first_flag_cannot_turn_unknown_display_into_valid_first(self):
        value = self.jittered_report()
        value.pop("measurementStart")
        value["samples"][0]["isFirstCallback"] = False
        self.assertEqual(self.selected(value)["acceptanceGateStatus"], "insufficient-evidence")
        value["samples"][50].update(isFirstCallback=True, displayTimestampIntervalMs=None)
        result = self.selected(value)
        self.assertEqual(result["acceptanceGateStatus"], "insufficient-evidence")
        self.assertIn("misplaced_first_callback", result["incomplete_or_truncated"])

    def test_zero_comparable_display_cannot_pass_or_change_legacy_arrival(self):
        for indices in ([60], list(range(1, len(report_v3()["samples"])))):
            value = report_v3()
            for index in indices:
                value["samples"][index]["displayTimestampIntervalMs"] = 0
            result = self.selected(value)
            self.assertEqual(result["acceptanceGateStatus"], "insufficient-evidence")
            self.assertIn("nonpositive_comparable_display_intervals", result["incomplete_or_truncated"])
            self.assertEqual(result["acceptance_policy_details"]["nonpositive_comparable_display_intervals"], len(indices))
            self.assertEqual(result["callbackGateStatus"], "pass")
            self.assertEqual(evaluate(value)["acceptanceGateStatus"], "pass", "保留legacy对零display间隔的原语义")

    def test_malformed_display_and_fabricated_first_timestamp_are_rejected(self):
        for number in (-1, float("nan"), float("inf"), True):
            value = self.jittered_report()
            value["samples"][60]["displayTimestampIntervalMs"] = number
            with self.assertRaises(ValueError):
                self.selected(value)
        value = self.jittered_report()
        value["samples"][0]["displayTimestampIntervalMs"] = 16.666
        with self.assertRaises(ValueError):
            self.selected(value)

    def test_lifecycle_anchor_overlap_truncation_and_tail_still_gate(self):
        for flag in ("sampleLimitReached", "eventLimitReached", "unfinishedPagination",
                     "unfinishedPreparation", "unfinishedCommit", "unfinishedGesture", "lifecycleMismatch"):
            value = self.jittered_report()
            value[flag] = True
            self.assertEqual(self.selected(value)["acceptanceGateStatus"], "insufficient-evidence", flag)
        value = self.jittered_report()
        event(value, "history_commit_end")["anchorErrorPt"] = 2
        self.assertEqual(self.selected(value)["acceptanceGateStatus"], "fail")
        value = self.jittered_report()
        for sample in value["samples"]:
            sample["isScrolling"] = False
        self.assertEqual(self.selected(value)["acceptanceGateStatus"], "insufficient-evidence")
        value = self.jittered_report()
        value["trailingActiveUnobservedMs"] = 34
        self.assertEqual(self.selected(value)["acceptanceGateStatus"], "insufficient-evidence")
        value = self.jittered_report()
        value["durationSeconds"] = 2.4
        self.assertEqual(self.selected(value)["acceptanceGateStatus"], "insufficient-evidence")

    def test_schema2_and_empty_operations_cannot_establish_new_acceptance(self):
        result = self.selected(report())
        self.assertEqual(result["callbackGateStatus"], "pass", "保留原schema2 arrival口径")
        self.assertEqual(result["acceptanceGateStatus"], "insufficient-evidence")
        self.assertIn("display_cadence_requires_schema3", result["incomplete_or_truncated"])
        value = report_v3()
        value["events"] = [event(value, "history_load_requested"), event(value, "history_operation_terminal"),
                           dict(event(value, "history_operation_complete"), outcome="empty")]
        self.assertEqual(self.selected(value)["acceptanceGateStatus"], "insufficient-evidence")

    def test_cli_requires_explicit_policy_and_writes_separate_report(self):
        import contextlib
        import io
        import json
        from pathlib import Path
        import tempfile
        from unittest.mock import patch
        from summarize_ios_history_scroll import main

        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            raw, old, new = (directory / name for name in ("raw.json", "old-summary.json", "display-summary.json"))
            raw.write_text(json.dumps(self.jittered_report()))
            old.write_text("old evidence remains untouched\n")
            raw_before = raw.read_bytes()
            with patch("sys.argv", ["summarize", str(raw)]), contextlib.redirect_stdout(io.StringIO()) as output:
                main()
            self.assertEqual(json.loads(output.getvalue())["acceptance_policy"], "legacy-arrival")
            with patch("sys.argv", ["summarize", str(raw), "--gate-policy", "display-cadence", "--output", str(new)]), contextlib.redirect_stdout(io.StringIO()):
                main()
            result = json.loads(new.read_text())
            self.assertEqual(result["acceptanceGateStatus"], "pass")
            self.assertEqual(result["callbackGateStatus"], "fail")
            self.assertEqual(raw.read_bytes(), raw_before)
            self.assertEqual(old.read_text(), "old evidence remains untouched\n")

    def test_unknown_policy_is_rejected(self):
        with self.assertRaises(ValueError):
            evaluate(report_v3(), gate_policy="auto")


def interaction_report():
    value = report_v3()
    value.update(schedulerEventLimitReached=False, durationSeconds=2.6, trailingActiveUnobservedMs=0)
    for phase in ("history_commit_begin", "history_commit_end"):
        event(value, phase)["insertedRows"] = 2
    add_event(value, {"phase": "scroll_begin", "elapsedSeconds": 0})
    add_event(value, {"phase": "scroll_end", "elapsedSeconds": .8})
    for index, sample in enumerate(value["samples"]):
        at = (index + 1) / 60
        sample.update(elapsedSeconds=at, callbackArrivalIntervalMs=1000 / 60,
                      previousArrivalElapsedSeconds=None if index == 0 else index / 60,
                      displayTimestampElapsedSeconds=at - .001,
                      displayTimestampIntervalMs=None if index == 0 else 1000 / 60,
                      isFirstCallback=index == 0, isScrolling=at <= .8,
                      isHistoryLoading=.5 <= at < 1.5, loadOrdinal=1 if .5 <= at < 1.5 else None)
    def scheduler(stage, at, **extra):
        result = dict(stage=stage, elapsedSeconds=at, loadOrdinal=1, generation=1, entries=6,
                      batchEntries=6, insertedEntries=0, remainingEntries=2,
                      moving=at < .8, acceptedAgeMs=(at - .5) * 1000,
                      route="prepend", changedExistingCount=0)
        if stage in {"commit_admitted", "batch_presented", "final_ack"}: result["projectionEpoch"] = 2
        if at >= .61:
            result["bufferedAgeMs"] = (at - .61) * 1000
        result.update(extra)
        if stage in {"commit_admitted", "batch_presented"}:
            result.update(insertedBodyEntries=result["insertedEntries"], insertedSupplementalEntries=0)
        return result
    value["schedulerEvents"] = [
        scheduler("accepted", .5, entries=4, batchEntries=0, remainingEntries=0),
        scheduler("target_buffered", .61, entries=0, batchEntries=0, remainingEntries=0),
        scheduler("idle_wait", .8, entries=0, batchEntries=0, remainingEntries=0),
        scheduler("target_buffered", .92),
        scheduler("prepare_admitted", .95, batchEntries=1),
        scheduler("preparation_finished", .96, batchEntries=1),
        scheduler("commit_admitted", 1.4, insertedEntries=2, remainingEntries=0),
        scheduler("batch_presented", 1.45, insertedEntries=2, remainingEntries=0, firstPresentation=True),
        scheduler("final_ack", 1.459, remainingEntries=0),
    ]
    return value


class InteractionPriorityTests(unittest.TestCase):
    def selected(self, value):
        return evaluate(value, gate_policy="display-cadence", experience_policy="interaction-priority")

    def scheduler(self, value, stage, occurrence=0):
        return [item for item in value["schedulerEvents"] if item["stage"] == stage][occurrence]

    def test_complete_interaction_report_passes_without_replacing_existing_acceptance(self):
        value = interaction_report()
        baseline = evaluate(value, gate_policy="display-cadence")
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "pass")
        self.assertEqual(result["experience"]["renderFPSStatus"], "unsupported")
        for key, actual in baseline.items():
            self.assertEqual(result[key], actual, key)
        operation = result["experience"]["scheduler"]["operations"][0]
        self.assertAlmostEqual(operation["first_presentation_delay_ms"], 950)
        self.assertAlmostEqual(operation["final_presentation_delay_ms"], 959)
        self.assertAlmostEqual(operation["operation_completion_delay_ms"], 1000)

    def test_old_raw_without_new_clocks_or_scheduler_is_insufficient_only_for_experience(self):
        value = report_v3()
        result = self.selected(value)
        self.assertEqual(result["acceptanceGateStatus"], "pass")
        self.assertEqual(result["experienceGateStatus"], "insufficient-evidence")
        self.assertIn("missing_scheduler_evidence", result["experience"]["insufficient_evidence"])
        self.assertIn("missing_or_invalid_display_absolute_clock", result["experience"]["insufficient_evidence"])

    def test_schema2_is_still_readable_but_not_new_experience_evidence(self):
        result = evaluate(report(), experience_policy="interaction-priority")
        self.assertEqual(result["acceptanceGateStatus"], "pass")
        self.assertEqual(result["experienceGateStatus"], "insufficient-evidence")

    def test_healthy_movement_and_25ms_idle_keep_old_failure_but_new_policy_may_pass(self):
        value = interaction_report()
        times = [index / 60 for index in range(1, 49)] + [.8 + index * .025 for index in range(1, 73)]
        previous = 0
        value["samples"] = value["samples"][:len(times)]
        for index, (sample, at) in enumerate(zip(value["samples"], times)):
            delta = (at - previous) * 1000
            sample.update(elapsedSeconds=at, callbackArrivalIntervalMs=delta,
                          previousArrivalElapsedSeconds=None if index == 0 else previous,
                          displayTimestampElapsedSeconds=at - .001,
                          displayTimestampIntervalMs=delta if index else None,
                          isFirstCallback=index == 0, isScrolling=at <= .8,
                          isHistoryLoading=.5 <= at < 1.5, loadOrdinal=1 if .5 <= at < 1.5 else None)
            previous = at
        result = self.selected(value)
        self.assertEqual(result["acceptanceGateStatus"], "fail")
        self.assertEqual(result["callbackGateStatus"], "fail")
        self.assertEqual(result["experienceGateStatus"], "pass")
        self.assertGreater(result["experience"]["clocks"]["display"]["idle"]["p95_ms"], 16.7)

    def test_entire_crossing_interval_is_counted_once_even_after_scroll_end(self):
        from summarize_ios_history_scroll import interaction_clocks
        value = {"samples": [
            dict(elapsedSeconds=.1, callbackArrivalIntervalMs=100, isFirstCallback=True,
                 displayTimestampElapsedSeconds=.09, displayTimestampIntervalMs=None),
            dict(elapsedSeconds=.3, callbackArrivalIntervalMs=200, isFirstCallback=False,
                 displayTimestampElapsedSeconds=.29, displayTimestampIntervalMs=200)]}
        issues = []
        clocks, _ = interaction_clocks(value, [(.05, .15), (.2, .25)], issues)
        self.assertEqual(issues, [])
        for clock in ("arrival", "display"):
            self.assertEqual(clocks[clock]["movement"]["maximum_ms"], 200)
            self.assertEqual(clocks[clock]["multiple_movement_windows_intervals"], 1)
            self.assertEqual(clocks[clock]["idle"]["sample_count"], 0)

    def test_two_clocks_are_classified_independently_and_never_from_arrival_flag(self):
        from summarize_ios_history_scroll import interaction_clocks
        value = {"samples": [
            dict(elapsedSeconds=.08, callbackArrivalIntervalMs=80, isFirstCallback=True,
                 displayTimestampElapsedSeconds=.04, displayTimestampIntervalMs=None),
            dict(elapsedSeconds=.10, callbackArrivalIntervalMs=20, isFirstCallback=False,
                 displayTimestampElapsedSeconds=.06, displayTimestampIntervalMs=20, isScrolling=False)]}
        issues = []
        clocks, _ = interaction_clocks(value, [(.045, .055)], issues)
        self.assertEqual(issues, [])
        self.assertEqual(clocks["arrival"]["idle"]["sample_count"], 1)
        self.assertEqual(clocks["display"]["movement"]["sample_count"], 1)
        self.assertEqual(clocks["display"]["movement"]["maximum_ms"], 20)

    def test_touching_endpoint_is_conservative_movement_and_is_reported(self):
        from summarize_ios_history_scroll import interaction_clocks
        value = {"samples": [dict(elapsedSeconds=.1, callbackArrivalIntervalMs=100,
                                  isFirstCallback=True, displayTimestampElapsedSeconds=.09)]}
        clocks, _ = interaction_clocks(value, [(.1, .2)], [])
        self.assertEqual(clocks["arrival"]["boundary_touch_only_intervals"], 1)
        self.assertEqual(clocks["arrival"]["movement"]["maximum_ms"], 100)

    def shift_arrival(self, value, index, delta):
        for sample in value["samples"][index:]:
            sample["elapsedSeconds"] += delta
        for i, sample in enumerate(value["samples"]):
            sample["callbackArrivalIntervalMs"] = (sample["elapsedSeconds"] - (value["samples"][i - 1]["elapsedSeconds"] if i else 0)) * 1000
            sample["previousArrivalElapsedSeconds"] = value["samples"][i - 1]["elapsedSeconds"] if i else None
            sample.update(isHistoryLoading=.5 <= sample["elapsedSeconds"] < 1.5,
                          loadOrdinal=1 if .5 <= sample["elapsedSeconds"] < 1.5 else None)
        value["durationSeconds"] = max(value["durationSeconds"], value["samples"][-1]["elapsedSeconds"])

    def test_idle_arrival_100ms_is_global_hard_failure_even_with_healthy_movement(self):
        value = interaction_report()
        self.shift_arrival(value, 70, .084)
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "fail")
        self.assertIn("all_active_arrival_stall_at_least_100ms", result["experience"]["failures"])
        self.assertEqual(result["experience"]["clocks"]["arrival"]["movement"]["stalls_at_least_100_ms"], 0)
        self.assertGreaterEqual(result["experience"]["clocks"]["arrival"]["idle"]["stalls_at_least_100_ms"], 1)

    def test_idle_display_100ms_is_public_observation_but_old_gate_still_fails(self):
        value = interaction_report()
        self.shift_arrival(value, 58, .042)
        self.shift_arrival(value, 59, .042)
        for sample in value["samples"][60:]:
            sample["displayTimestampElapsedSeconds"] += .084
        value["samples"][60]["displayTimestampIntervalMs"] += 84
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "pass")
        self.assertEqual(result["acceptanceGateStatus"], "fail")
        self.assertEqual(result["experience"]["clocks"]["display"]["idle"]["stalls_at_least_100_ms"], 1)
        self.assertEqual(result["experience"]["clocks"]["arrival"]["all"]["stalls_at_least_100_ms"], 0)

    def test_missing_zero_reversed_or_inconsistent_display_clock_never_passes(self):
        for mutation in ("missing", "zero", "reverse", "delta", "future", "nan"):
            value = interaction_report()
            sample = value["samples"][60]
            if mutation == "missing": sample.pop("displayTimestampElapsedSeconds")
            elif mutation == "zero": sample["displayTimestampIntervalMs"] = 0
            elif mutation == "reverse": sample["displayTimestampElapsedSeconds"] = .1
            elif mutation == "delta": sample["displayTimestampElapsedSeconds"] -= .001
            elif mutation == "future": sample["displayTimestampElapsedSeconds"] += 1
            else: sample["displayTimestampElapsedSeconds"] = float("nan")
            self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence", mutation)

    def test_first_negative_display_absolute_time_is_not_clamped(self):
        value = interaction_report()
        for sample in value["samples"]:
            sample["displayTimestampElapsedSeconds"] -= .03
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "pass")
        self.assertLess(value["samples"][0]["displayTimestampElapsedSeconds"], 0)
        self.assertEqual(result["experience"]["clocks"]["display"]["all"]["sample_count"], 155)

    def test_inactive_collection_gap_is_not_fabricated_as_a_long_callback_interval(self):
        value = interaction_report()
        add_event(value, dict(phase="scroll_begin", elapsedSeconds=3.2))
        add_event(value, dict(phase="scroll_end", elapsedSeconds=3.6))
        template = dict(value["samples"][-1])
        for index in range(60):
            at = 3.2 + (index + 1) / 60
            value["samples"].append(dict(template, elapsedSeconds=at, displayTimestampElapsedSeconds=at - .001,
                                          previousArrivalElapsedSeconds=at - 1 / 60,
                                          activationClipped=index == 0, isScrolling=at <= 3.6))
        value["durationSeconds"] = 4.2
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "pass")
        self.assertEqual(result["experience"]["clocks"]["arrival"]["omitted_inactive_gaps"], 1)
        self.assertLess(result["experience"]["clocks"]["display"]["all"]["maximum_ms"], 17)

    def test_activation_flag_cannot_hide_missing_samples_during_pending_or_movement(self):
        for index in (20, 65):
            value = interaction_report()
            del value["samples"][index:index + 4]
            value["samples"][index]["activationClipped"] = True
            self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence")

    def test_first_stored_sample_can_follow_real_unstored_idle_callbacks(self):
        value = interaction_report()
        value["samples"][0].update(isFirstCallback=False, activationClipped=True,
                                    previousArrivalElapsedSeconds=0,
                                    displayTimestampIntervalMs=1000 / 60)
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "pass")
        self.assertEqual(result["experience"]["clocks"]["display"]["all"]["sample_count"], 156)
        value["samples"][0]["activationClipped"] = False
        self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence")

    def test_activation_clipped_original_delta_cannot_hide_a_full_100ms_arrival(self):
        value = interaction_report()
        self.shift_arrival(value, 70, .11)
        value["samples"][70].update(activationClipped=True, callbackArrivalIntervalMs=1000 / 60)
        result = self.selected(value)
        self.assertEqual(result["acceptanceGateStatus"], "pass")
        self.assertEqual(result["experienceGateStatus"], "fail")
        self.assertIn("all_active_arrival_stall_at_least_100ms", result["experience"]["failures"])
        self.assertGreater(result["experience"]["clocks"]["arrival"]["all"]["maximum_ms"], 126)

    def test_clipped_nonfirst_missing_previous_arrival_is_insufficient_not_guessed(self):
        value = interaction_report()
        value["samples"][70].update(activationClipped=True)
        value["samples"][70].pop("previousArrivalElapsedSeconds")
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "insufficient-evidence")
        self.assertIn("missing_previous_arrival_for_activation_clipped_sample", result["experience"]["insufficient_evidence"])

    def test_unclipped_sample_can_use_original_delta_but_explicit_previous_clock_must_agree(self):
        value = interaction_report()
        value["samples"][70].pop("previousArrivalElapsedSeconds")
        self.assertEqual(self.selected(value)["experienceGateStatus"], "pass")
        for prior in (None, float("nan"), 2, .1):
            value = interaction_report()
            if prior is None: value["samples"][0]["previousArrivalElapsedSeconds"] = 0
            else: value["samples"][70]["previousArrivalElapsedSeconds"] = prior
            self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence", prior)

    def test_unclosed_nested_or_missing_gesture_evidence_is_insufficient(self):
        for mutation in ("unclosed", "nested", "missing"):
            value = interaction_report()
            if mutation == "unclosed":
                value["events"] = [e for e in value["events"] if e["phase"] != "scroll_end"]
            elif mutation == "nested": add_event(value, dict(phase="scroll_begin", elapsedSeconds=.1))
            else: value["events"] = [e for e in value["events"] if not e["phase"].startswith("scroll_")]
            self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence", mutation)

    def test_missing_truncated_unknown_or_out_of_order_scheduler_cannot_pass(self):
        for mutation in ("missing", "truncated", "unknown", "order", "capacity"):
            value = interaction_report()
            if mutation == "missing": value.pop("schedulerEvents")
            elif mutation == "truncated": value["schedulerEventLimitReached"] = True
            elif mutation == "unknown": value["schedulerEvents"][2]["stage"] = "guessed_idle"
            elif mutation == "order": value["schedulerEvents"][0], value["schedulerEvents"][1] = value["schedulerEvents"][1], value["schedulerEvents"][0]
            else: value["schedulerEvents"] *= 30
            self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence", mutation)

    def test_movement_admission_flags_and_actual_windows_both_gate(self):
        value = interaction_report()
        self.scheduler(value, "prepare_admitted")["moving"] = True
        self.assertEqual(self.selected(value)["experienceGateStatus"], "fail")
        value = interaction_report()
        self.scheduler(value, "prepare_admitted").update(elapsedSeconds=.7, acceptedAgeMs=200, bufferedAgeMs=90, moving=False)
        value["schedulerEvents"].sort(key=lambda e: e["elapsedSeconds"])
        self.assertEqual(self.selected(value)["experienceGateStatus"], "fail")

    def test_continuous_idle_120ms_and_per_batch_limits_are_not_relaxed(self):
        for stage, key, number in (("prepare_admitted", "batchEntries", 2),
                                   ("commit_admitted", "insertedEntries", 3)):
            value = interaction_report()
            self.scheduler(value, stage)[key] = number
            result = self.selected(value)
            self.assertNotEqual(result["experienceGateStatus"], "pass")
        value = interaction_report()
        self.scheduler(value, "prepare_admitted").update(elapsedSeconds=.919, acceptedAgeMs=419, bufferedAgeMs=309)
        value["schedulerEvents"].sort(key=lambda e: e["elapsedSeconds"])
        self.assertEqual(self.selected(value)["experienceGateStatus"], "fail")

    def test_no_final_ack_unpaired_commit_or_missing_batch_is_insufficient(self):
        for stage in ("final_ack", "batch_presented", "commit_admitted", "preparation_finished"):
            value = interaction_report()
            value["schedulerEvents"] = [e for e in value["schedulerEvents"] if e["stage"] != stage]
            self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence", stage)

    def test_final_ack_requires_zero_remaining_latest_generation_and_actual_final_commit(self):
        for mutation in ("remaining", "generation", "early", "firstflag", "inserted"):
            value = interaction_report()
            ack = self.scheduler(value, "final_ack")
            if mutation == "remaining": ack["remainingEntries"] = 1
            elif mutation == "generation": ack["generation"] = 0
            elif mutation == "early": ack.update(elapsedSeconds=1.44, acceptedAgeMs=940, bufferedAgeMs=830)
            elif mutation == "firstflag": self.scheduler(value, "batch_presented")["firstPresentation"] = False
            else: self.scheduler(value, "batch_presented")["insertedEntries"] = 1
            value["schedulerEvents"].sort(key=lambda e: e["elapsedSeconds"])
            self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence", mutation)

    def test_latest_target_merge_never_resets_accepted_or_buffered_age(self):
        value = interaction_report()
        merged = dict(self.scheduler(value, "target_buffered", 1), elapsedSeconds=1.1, generation=2,
                      acceptedAgeMs=600, bufferedAgeMs=490)
        value["schedulerEvents"].append(merged)
        for e in value["schedulerEvents"]:
            if e["elapsedSeconds"] > 1.1: e["generation"] = 2
        value["schedulerEvents"].sort(key=lambda e: e["elapsedSeconds"])
        self.assertEqual(self.selected(value)["experienceGateStatus"], "pass")
        merged["acceptedAgeMs"] = 0
        self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence")
        merged["acceptedAgeMs"], merged["bufferedAgeMs"] = 600, 0
        self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence")

    def test_cached_batch_requires_no_synthetic_preparation(self):
        value = interaction_report()
        value["schedulerEvents"] = [e for e in value["schedulerEvents"]
                                    if e["stage"] not in {"prepare_admitted", "preparation_finished"}]
        self.assertEqual(self.selected(value)["experienceGateStatus"], "pass")

    def test_changed_existing_rows_can_require_multiple_preparations_before_one_commit(self):
        value = interaction_report()
        for at, stage in ((1.0, "prepare_admitted"), (1.01, "preparation_finished"),
                          (1.05, "prepare_admitted"), (1.06, "preparation_finished")):
            source = self.scheduler(value, stage)
            value["schedulerEvents"].append(dict(source, elapsedSeconds=at,
                                                 acceptedAgeMs=(at - .5) * 1000,
                                                 bufferedAgeMs=(at - .61) * 1000))
        for stage in ("commit_admitted", "batch_presented"):
            self.scheduler(value, stage)["changedExistingCount"] = 3
        value["schedulerEvents"].sort(key=lambda e: e["elapsedSeconds"])
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "pass")
        self.assertEqual(len(result["experience"]["scheduler"]["preparation_row_durations_ms"]), 3)
        self.assertEqual(result["experience"]["scheduler"]["batches"][0]["changed_existing_count"], 3)

    def two_batches(self, chrome_first=False):
        value = interaction_report()
        inserted = 1 if chrome_first else 2
        add_event(value, dict(phase="history_commit_begin", elapsedSeconds=.98, loadOrdinal=1, insertedRows=inserted))
        add_event(value, dict(phase="history_commit_end", elapsedSeconds=.99, loadOrdinal=1,
                              insertedRows=inserted, anchorValid=True, anchorErrorPt=.1))
        for stage, at in (("commit_admitted", .98), ("batch_presented", .99)):
            extra = dict(self.scheduler(value, stage), elapsedSeconds=at, acceptedAgeMs=(at - .5) * 1000,
                         bufferedAgeMs=(at - .61) * 1000, remainingEntries=2, insertedEntries=inserted,
                         insertedBodyEntries=0 if chrome_first else inserted,
                         insertedSupplementalEntries=inserted if chrome_first else 0)
            if stage == "batch_presented": extra["firstPresentation"] = not chrome_first
            value["schedulerEvents"].append(extra)
        self.scheduler(value, "batch_presented")["firstPresentation"] = chrome_first
        value["schedulerEvents"].sort(key=lambda e: e["elapsedSeconds"])
        return value

    def test_multiple_actual_batches_do_not_ack_the_first_partial_batch(self):
        value = self.two_batches()
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "pass")
        self.assertEqual(result["experience"]["scheduler"]["operations"][0]["batch_count"], 2)
        self.scheduler(value, "final_ack").update(elapsedSeconds=.995, acceptedAgeMs=495, bufferedAgeMs=385)
        value["schedulerEvents"].sort(key=lambda e: e["elapsedSeconds"])
        self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence")

    def test_full_early_epoch_ack_does_not_release_a_later_required_epoch(self):
        value = self.two_batches()
        for phase, at in (("history_operation_committed", .993), ("history_operation_presented", .999)):
            add_event(value, dict(phase=phase, elapsedSeconds=at, loadOrdinal=1, projectionEpoch=2))
        # Only the final pair is epoch 3; the first complete target was genuinely epoch 2.
        for e in value["events"]:
            if e["phase"] in {"history_operation_committed", "history_operation_presented"} and e["elapsedSeconds"] > 1:
                e["projectionEpoch"] = 3
        add_event(value, dict(phase="history_operation_required", elapsedSeconds=1.01, loadOrdinal=1, projectionEpoch=3))
        add_event(value, dict(phase="history_operation_issued", elapsedSeconds=1.02, loadOrdinal=1, projectionEpoch=3))
        for e in value["schedulerEvents"]:
            if e["stage"] in {"commit_admitted", "batch_presented"} and e["elapsedSeconds"] < 1:
                e["remainingEntries"] = 0
            if e["elapsedSeconds"] > 1:
                e.update(generation=2, projectionEpoch=3)
        ack = dict(self.scheduler(value, "final_ack"), elapsedSeconds=.9985, generation=1, projectionEpoch=2,
                   acceptedAgeMs=498.5, bufferedAgeMs=388.5)
        target = dict(self.scheduler(value, "target_buffered", 1), elapsedSeconds=1.03, generation=2,
                      projectionEpoch=3, acceptedAgeMs=530, bufferedAgeMs=420)
        value["schedulerEvents"].extend([ack, target])
        value["schedulerEvents"].sort(key=lambda e: e["elapsedSeconds"])
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "pass")
        operation = result["experience"]["scheduler"]["operations"][0]
        self.assertEqual(len(operation["acknowledgements"]), 2)
        self.assertAlmostEqual(operation["final_presentation_delay_ms"], 959)
        value["schedulerEvents"].remove(self.scheduler(value, "final_ack", 1))
        self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence")

    def test_same_history_epoch_live_target_generation_does_not_starve_its_ack(self):
        value = interaction_report()
        target = dict(self.scheduler(value, "target_buffered", 1), elapsedSeconds=1.43, generation=2,
                      projectionEpoch=2, acceptedAgeMs=930, bufferedAgeMs=820)
        value["schedulerEvents"].append(target)
        value["schedulerEvents"].sort(key=lambda e: e["elapsedSeconds"])
        self.assertEqual(self.selected(value)["experienceGateStatus"], "pass")

    def test_missing_unissued_or_mismatched_scheduler_epoch_cannot_pass(self):
        for mutation in ("missing", "future", "pair", "ack"):
            value = interaction_report()
            if mutation == "missing": self.scheduler(value, "final_ack").pop("projectionEpoch")
            elif mutation == "future":
                for stage in ("commit_admitted", "batch_presented", "final_ack"):
                    self.scheduler(value, stage)["projectionEpoch"] = 999
            elif mutation == "pair": self.scheduler(value, "batch_presented")["projectionEpoch"] = 3
            else: self.scheduler(value, "final_ack")["projectionEpoch"] = 999
            self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence", mutation)

    def test_chrome_only_positive_commit_does_not_claim_first_body_presentation(self):
        value = self.two_batches(chrome_first=True)
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "pass")
        self.assertAlmostEqual(result["experience"]["scheduler"]["operations"][0]["first_presentation_delay_ms"], 950)
        self.scheduler(value, "batch_presented")["firstPresentation"] = True
        self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence")

    def test_missing_or_inconsistent_body_classification_never_passes(self):
        for mutation in ("missing", "sum", "bool", "pair"):
            value = interaction_report()
            presented = self.scheduler(value, "batch_presented")
            if mutation == "missing": presented.pop("insertedBodyEntries")
            elif mutation == "sum": presented["insertedSupplementalEntries"] = 1
            elif mutation == "bool": presented["insertedBodyEntries"] = True
            else: presented.update(insertedBodyEntries=1, insertedSupplementalEntries=1)
            self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence", mutation)

    def test_explicit_replacement_is_reported_as_full_scale_not_bounded_prepend(self):
        value = interaction_report()
        for phase in ("history_commit_begin", "history_commit_end"):
            event(value, phase)["insertedRows"] = 5
        for stage in ("commit_admitted", "batch_presented"):
            self.scheduler(value, stage).update(route="replacement", insertedEntries=5, insertedBodyEntries=5,
                                                batchEntries=20, entries=20)
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "pass")
        self.assertEqual(result["experience"]["scheduler"]["maximum_replacement_batch_entries"], 20)
        self.assertEqual(result["experience"]["scheduler"]["presentation_route_counts"], {"replacement": 1})
        for stage in ("commit_admitted", "batch_presented"):
            self.scheduler(value, stage)["route"] = "prepend"
        self.assertEqual(self.selected(value)["experienceGateStatus"], "fail")

    def test_missing_unknown_or_inconsistent_route_cannot_hide_unbounded_batch(self):
        for route in (None, "fallback", "in_place"):
            value = interaction_report()
            for stage in ("commit_admitted", "batch_presented"):
                self.scheduler(value, stage)["route"] = route
            self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence", route)

    def test_commit_before_preparation_finishes_is_insufficient(self):
        value = interaction_report()
        self.scheduler(value, "preparation_finished").update(elapsedSeconds=1.42, acceptedAgeMs=920, bufferedAgeMs=810)
        value["schedulerEvents"].sort(key=lambda e: e["elapsedSeconds"])
        self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence")

    def test_movement_display_long_interval_still_fails_when_arrivals_stay_under_100ms(self):
        value = interaction_report()
        self.shift_arrival(value, 30, .042)
        self.shift_arrival(value, 31, .042)
        for sample in value["samples"][32:]: sample["displayTimestampElapsedSeconds"] += .084
        value["samples"][32]["displayTimestampIntervalMs"] += 84
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "fail")
        self.assertIn("movement_display_stall_at_least_100ms", result["experience"]["failures"])
        self.assertEqual(result["experience"]["clocks"]["arrival"]["all"]["stalls_at_least_100_ms"], 0)

    def test_empty_operation_does_not_need_a_fake_ack_when_another_positive_operation_completes(self):
        value = interaction_report()
        value["acceptedCallCount"] = 2
        for e in value["events"]:
            if e.get("loadOrdinal") == 1: e["loadOrdinal"] = 2
            if e.get("acceptedCallOrdinal") == 1: e["acceptedCallOrdinal"] = 2
        for e in value["schedulerEvents"]: e["loadOrdinal"] = 2
        for s in value["samples"]:
            if s.get("loadOrdinal") == 1: s["loadOrdinal"] = 2
            if .1 <= s["elapsedSeconds"] < .2: s.update(loadOrdinal=1, isHistoryLoading=True)
        add_event(value, dict(phase="history_load_requested", elapsedSeconds=.1, loadOrdinal=1,
                              acceptedCallOrdinal=1, coalesced=False))
        add_event(value, dict(phase="history_operation_terminal", elapsedSeconds=.18, loadOrdinal=1))
        add_event(value, dict(phase="history_operation_complete", elapsedSeconds=.2, loadOrdinal=1, outcome="empty"))
        value["schedulerEvents"].insert(0, dict(stage="accepted", elapsedSeconds=.1, loadOrdinal=1,
                                               generation=0, entries=4, batchEntries=0, insertedEntries=0,
                                               remainingEntries=0, moving=True, acceptedAgeMs=0,
                                               changedExistingCount=0))
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "pass")
        empty = result["experience"]["scheduler"]["operations"][0]
        self.assertEqual(empty["outcome"], "empty")
        self.assertIsNone(empty["first_presentation_delay_ms"])
        self.assertIsNone(empty["final_presentation_delay_ms"])

    def test_cancelled_or_work_after_operation_complete_cannot_be_a_complete_experience(self):
        for stage in ("cancelled", "target_buffered", "final_ack"):
            value = interaction_report()
            value["schedulerEvents"].append(dict(self.scheduler(value, "final_ack"), stage=stage,
                                                 elapsedSeconds=1.51, acceptedAgeMs=1010, bufferedAgeMs=900))
            self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence", stage)

    def test_malformed_scheduler_record_fails_closed_without_altering_legacy_results(self):
        value = interaction_report()
        value["schedulerEvents"].append(None)
        baseline = evaluate(value, gate_policy="display-cadence")
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "insufficient-evidence")
        for key, actual in baseline.items(): self.assertEqual(result[key], actual)
    def test_empty_or_unfinished_operation_cannot_be_experience_pass(self):
        value = interaction_report()
        value.update(unfinishedPagination=True)
        value["events"] = [e for e in value["events"] if e["phase"] != "history_operation_complete"]
        for sample in value["samples"]:
            if sample["elapsedSeconds"] >= .5: sample.update(isHistoryLoading=True, loadOrdinal=1)
        result = self.selected(value)
        self.assertEqual(result["experienceGateStatus"], "insufficient-evidence")
        operation = result["experience"]["scheduler"]["operations"][0]
        self.assertEqual(operation["pending_age_ms_at_completion_or_capture_end"], 2100)
        self.assertIsNone(operation["operation_completion_delay_ms"])
        value = interaction_report()
        value["events"] = [e for e in value["events"] if e["phase"] in ("scroll_begin", "scroll_end", "history_load_requested", "history_operation_terminal", "history_operation_complete")]
        event(value, "history_operation_complete")["outcome"] = "empty"
        self.assertEqual(self.selected(value)["experienceGateStatus"], "insufficient-evidence")

    def test_anchor_lifecycle_settle_and_real_pending_overlap_still_required(self):
        for mutation in ("anchor", "epoch", "settle", "overlap"):
            value = interaction_report()
            if mutation == "anchor": event(value, "history_commit_end")["anchorErrorPt"] = 2
            elif mutation == "epoch": event(value, "history_operation_presented")["projectionEpoch"] = 999
            elif mutation == "settle": value["durationSeconds"] = 2.4
            else: event(value, "scroll_end")["elapsedSeconds"] = .49
            self.assertNotEqual(self.selected(value)["experienceGateStatus"], "pass", mutation)

    def test_unknown_experience_policy_raises(self):
        with self.assertRaises(ValueError):
            evaluate(interaction_report(), experience_policy="guessed")

    def test_cli_experience_report_is_explicit_separate_and_preserves_original_input(self):
        import contextlib
        import io
        import json
        from pathlib import Path
        import tempfile
        from unittest.mock import patch
        from summarize_ios_history_scroll import main
        with tempfile.TemporaryDirectory() as directory:
            raw = Path(directory) / "raw.json"
            new = Path(directory) / "summary-experience.json"
            raw.write_text(json.dumps(interaction_report()))
            before = raw.read_bytes()
            with patch("sys.argv", ["summarize", str(raw), "--experience-policy", "interaction-priority", "--output", str(new)]), contextlib.redirect_stdout(io.StringIO()):
                main()
            result = json.loads(new.read_text())
            self.assertEqual(result["experienceGateStatus"], "pass")
            self.assertEqual(result["acceptance_policy"], "legacy-arrival")
            self.assertEqual(raw.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
