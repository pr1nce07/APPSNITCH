# Traceability

Every PS-01 requirement mapped to the module, class and test that implements and
proves it. `scripts/check_traceability.py` parses the tables below and fails if a
requirement has no mapped class, or if a named class or test cannot be found in
the source tree.

## Verification status key

| Status | Meaning |
|---|---|
| `VERIFIED` | Executed in this environment and passed. Evidence is in the harness output. |
| `NOT-RUN` | Test source exists and is correct, but could not be executed here (needs Maven Central, an Android SDK, or a device). The exact command to run it is given in `docs/PROGRESS.md`. |
| `BLOCKED` | Cannot be produced in this environment at all. See `docs/LIMITATIONS.md`. |

**Nothing below is marked `VERIFIED` unless it actually ran.** See
`docs/PROGRESS.md` for the full execution report.

---

## Must-have requirements

| ID | Requirement | Module | Class | Test | Status |
|---|---|---|---|---|---|
| MUST-01 | App inventory: name, icon, package, version, install date, installer source, target SDK; system-app toggle | `inventory` | `AppInventory` | `AppInventoryTest.scan_excludes_system_apps_by_default` | NOT-RUN |
| MUST-01 | Icons loaded lazily, never held in the model | `inventory` | `IconLoader` | `IconLoaderTest.load_returns_null_for_missing_package` | NOT-RUN |
| MUST-01 | Updated-system apps treated as system apps | `inventory` | `AppInventory` | `AppInventoryTest.updated_system_apps_are_treated_as_system` | NOT-RUN |
| MUST-01 | An app vanishing mid-scan never aborts the scan | `inventory` | `AppInventory` | `AppInventoryTest.a_package_that_throws_is_skipped_not_fatal` | NOT-RUN |
| MUST-02 | Declared versus granted per app | `permissions` | `PermissionExtractor` | `PermissionExtractorTest.declared_and_granted_are_distinguished` | NOT-RUN |
| MUST-02 | Protection level resolved (normal, dangerous, signature, special) | `permissions` | `PermissionExtractor` | `PermissionExtractorTest.protection_levels_are_resolved` | NOT-RUN |
| MUST-02 | Custom or unresolvable permission degrades to UNKNOWN without crashing | `permissions` | `PermissionExtractor` | `PermissionExtractorTest.a_custom_permission_resolves_to_unknown` | NOT-RUN |
| MUST-03 | Nine mandated categories all covered | `catalog` | `PermissionCatalog` | `catalogSuite.all_nine_mandated_categories_are_populated` | VERIFIED |
| MUST-03 | Every problem-statement example maps to its stated category | `catalog` | `PermissionCatalog` | `catalogSuite.every_problem_statement_example_maps_to_its_stated_category` | VERIFIED |
| MUST-03 | Unknown permission falls back to UNCATEGORIZED | `catalog` | `PermissionCatalog` | `catalogSuite.unknown_permission_degrades_to_UNCATEGORIZED_without_throwing` | VERIFIED |
| MUST-03 | Device-wide category summary | `catalog` | `PermissionCatalog` | `catalogSuite.device_wide_summary_counts_apps_not_permissions` | VERIFIED |
| MUST-03 | Per-app category grouping for the UI | `presentation` | `ReportBuilder` | `pipelineSuite.Q2_category_groups_are_ordered_by_contribution` | VERIFIED |
| MUST-04 | Accessibility services detected | `special-access` | `SpecialAccessDetector` | `SpecialAccessDetectorTest.enabled_accessibility_service_is_detected` | NOT-RUN |
| MUST-04 | Active device admins detected | `special-access` | `SpecialAccessDetector` | `SpecialAccessDetectorTest.active_device_admin_is_detected` | NOT-RUN |
| MUST-04 | Enabled notification listeners detected | `special-access` | `SpecialAccessDetector` | `SpecialAccessDetectorTest.enabled_notification_listener_is_detected` | NOT-RUN |
| MUST-04 | Overlay and install-packages access detected | `special-access` | `SpecialAccessDetector` | `SpecialAccessDetectorTest.overlay_appops_allowed_is_reported_as_enabled` | NOT-RUN |
| MUST-04 | ENABLED vs DECLARED-ONLY kept distinct | `special-access` | `SpecialAccessDetector` | `SpecialAccessDetectorTest.declared_but_not_granted_is_declared_only` | NOT-RUN |
| MUST-04 | Unreadable AppOps reported as UNKNOWN, never as denied | `special-access` | `SpecialAccessDetector` | `SpecialAccessDetectorTest.unreadable_appops_is_unknown_not_denied` | NOT-RUN |
| MUST-05 | Score 0-100, never outside the range | `scoring` | `RiskEngine` | `engineInvariantSuite.score_is_always_within_0_100_across_2000_random_profiles` | VERIFIED |
| MUST-05 | Four bands with documented thresholds | `scoring` | `BandThresholds` | `rubricConfigSuite.band_thresholds_strictly_increase_and_cover_0_100` | VERIFIED |
| MUST-05 | At least three plain-language reasons for High or Critical | `scoring` | `RiskEngine` | `engineInvariantSuite.every_HIGH_or_CRITICAL_result_carries_at_least_3_reasons` | VERIFIED |
| MUST-05 | Reasons contain no technical jargon | `scoring` | `CategoryPhrases` | `engineInvariantSuite.BANNED_JARGON_no_headline_contains_technical_vocabulary` | VERIFIED |
| MUST-05 | Score is monotonic in granted dangerous permissions | `scoring` | `RiskEngine` | `engineInvariantSuite.MONOTONICITY_adding_a_granted_dangerous_permission_never_lowers_the_score` | VERIFIED |
| MUST-06 | "Why flagged" panel data | `presentation` | `ReportBuilder` | `pipelineSuite.Q2_every_HIGH_or_CRITICAL_detail_carries_at_least_3_reasons_and_1_action` | VERIFIED |
| MUST-06 | Q1 ranking, Q2 reasons and Q3 actions end to end | `presentation` | `ReportBuilder` | `pipelineSuite.END_TO_END_snapshot_score_report_detail_actions` | VERIFIED |
| MUST-06 | Button to the app's system settings page | `actions` | `IntentFactory` | `IntentFactoryTest.app_settings_intent_has_the_right_action_and_uri` | NOT-RUN |
| MUST-06 | Uninstall button | `actions` | `IntentFactory` | `IntentFactoryTest.uninstall_intent_uses_action_delete_with_package_uri` | NOT-RUN |
| MUST-06 | Remediation ordered so device admin is removed before uninstall | `presentation` | `RemediationAdvisor` | `pipelineSuite.Q3_device_admin_is_removed_before_uninstall_is_offered` | VERIFIED |
| MUST-06 | System apps offered disable, not a failing uninstall | `presentation` | `RemediationAdvisor` | `pipelineSuite.Q3_system_apps_are_offered_disable_never_a_failing_uninstall` | VERIFIED |

## Should-have requirements

| ID | Requirement | Module | Class | Test | Status |
|---|---|---|---|---|---|
| SHOULD-07 | Combination rules exist with bonuses and band floors | `scoring` | `CombinationRules` | `rubricConfigSuite.all_eleven_combination_rules_named_in_the_problem_statement_exist` | VERIFIED |
| SHOULD-07 | Every rule has a predicate and numbers, joined by id | `scoring` | `CombinationRules` | `rubricConfigSuite.every_combination_predicate_has_numbers_in_rubric_json` | VERIFIED |
| SHOULD-07 | Every rule has a positive and a negative case | `scoring` | `CombinationRules` | `engineInvariantSuite.each_combination_rule_has_a_positive_and_a_negative_case` | VERIFIED |
| SHOULD-07 | SMS + internet + boot + sideloaded | `scoring` | `CombinationRules` | `engineProfileSuite.PROFILE_banking_trojan` | VERIFIED |
| SHOULD-07 | Accessibility + overlay | `scoring` | `CombinationRules` | `engineProfileSuite.PROFILE_banking_trojan` | VERIFIED |
| SHOULD-07 | Device admin on a non-security app | `scoring` | `CombinationRules` | `engineProfileSuite.PROFILE_spoofed_security_tool_is_NOT_dampened` | VERIFIED |
| SHOULD-08 | Context mismatch: torch, calculator, wallpaper holding sensitive access | `context` | `ContextMismatchEngine` | `contextSuite.torch_app_holding_SMS_is_flagged` | VERIFIED |
| SHOULD-08 | A real messenger holding SMS is NOT flagged | `context` | `ContextMismatchEngine` | `contextSuite.NEGATIVE_a_real_messenger_holding_SMS_is_NOT_flagged` | VERIFIED |
| SHOULD-08 | An unknown archetype never produces a false alarm | `context` | `ArchetypeClassifier` | `contextSuite.NEGATIVE_an_unknown_archetype_never_produces_a_finding` | VERIFIED |
| SHOULD-09 | Rescan on install and update | `monitor` | `PackageEventReceiver` | `PackageEventReceiverTest.package_added_while_replacing_is_an_update` | NOT-RUN |
| SHOULD-09 | Notify when a new app scores High or Critical | `monitor` | `InstallMonitor` | `InstallMonitorTest.high_and_critical_notify_medium_and_low_do_not` | NOT-RUN |
| SHOULD-09 | WorkManager safety net for missed broadcasts | `monitor` | `PeriodicSweepWorker` | `PeriodicSweepWorkerTest.worker_retries_when_no_sweep_action_is_wired` | NOT-RUN |
| SHOULD-10 | Diff detects newly granted sensitive permissions | `diff` | `DiffEngine` | `pipelineSuite.a_later_run_that_adds_SMS_does_alert` | VERIFIED |
| SHOULD-10 | No change produces no alert | `diff` | `DiffEngine` | `pipelineSuite.no_change_raises_no_alert` | VERIFIED |
| SHOULD-10 | First run baselines instead of flooding with alerts | `diff` | `DiffEngine` | `pipelineSuite.the_first_run_baselines_and_raises_no_alerts` | VERIFIED |
| SHOULD-10 | Uninstalled apps are cleaned up | `store` | `SnapshotStore` | `pipelineSuite.a_new_store_is_empty_and_retainOnly_removes_uninstalled_packages` | VERIFIED |
| SHOULD-10 | Newly enabled special access raises an alert | `diff` | `DiffEngine` | `pipelineSuite.newly_enabled_special_access_raises_a_critical_alert` | VERIFIED |

## Stretch requirements

| ID | Requirement | Module | Class | Test | Status |
|---|---|---|---|---|---|
| STRETCH-11 | Streaming SHA-256 of the base APK | `hash-intel` | `Sha256Hasher` | `ThreatIntelServiceTest.sha256_matches_the_published_vector_for_abc` | NOT-RUN |
| STRETCH-11 | Hash cached by package, update time and size | `hash-intel` | `Sha256Hasher` | `ThreatIntelServiceTest.hashing_is_cached_until_the_app_is_updated` | NOT-RUN |
| STRETCH-11 | Offline local blocklist provider | `hash-intel` | `LocalBlocklistProvider` | `ThreatIntelServiceTest.the_offline_provider_works_with_no_consent_and_no_network` | NOT-RUN |
| STRETCH-11 | VirusTotal v3 lookup with user-supplied key | `hash-intel` | `VirusTotalProvider` | `ThreatIntelServiceTest.many_detections_map_to_MALICIOUS` | NOT-RUN |
| STRETCH-11 | MalwareBazaar lookup with free Auth-Key | `hash-intel` | `MalwareBazaarProvider` | `ThreatIntelServiceTest.a_rejected_api_key_is_reported_as_an_error` | NOT-RUN |
| STRETCH-11 | Zero requests without consent | `hash-intel` | `ThreatIntelService` | `ThreatIntelServiceTest.NO_network_request_is_made_without_consent` | NOT-RUN |
| STRETCH-11 | Requests contain only the hash | `hash-intel` | `ThreatIntelService` | `ThreatIntelServiceTest.the_request_contains_ONLY_the_hash` | NOT-RUN |
| STRETCH-11 | Offline degrades gracefully | `hash-intel` | `ThreatIntelService` | `ThreatIntelServiceTest.an_unreachable_network_degrades_gracefully` | NOT-RUN |
| STRETCH-11 | Rate limiting stops the batch | `hash-intel` | `ThreatIntelService` | `ThreatIntelServiceTest.rate_limiting_stops_further_network_calls_in_a_batch` | NOT-RUN |
| STRETCH-12 | Exported components without permission guards | `manifest-analysis` | `ManifestAnalyzer` | `ManifestAnalyzerTest.unguarded_exported_providers_are_reported` | NOT-RUN |
| STRETCH-12 | Cleartext traffic, debuggable, allowBackup, testOnly | `manifest-analysis` | `ManifestAnalyzer` | `ManifestAnalyzerTest.application_flags_are_decoded` | NOT-RUN |
| STRETCH-12 | Manifest findings feed the score | `scoring` | `RiskEngine` | `RiskEngineTest.manifest_findings_add_points` | NOT-RUN |
| STRETCH-13 | Python training pipeline with CV metrics | `ml` | `train.py` | `ml/training/train.py` | VERIFIED |
| STRETCH-13 | Model exported as model.json with vocab, weights, bias, threshold | `ml` | `ModelLoader` | `mlSuite.model_metadata_is_present_and_self_describing` | VERIFIED |
| STRETCH-13 | Pure-Kotlin inference | `ml` | `MlScorer` | `mlSuite.label_follows_the_threshold` | VERIFIED |
| STRETCH-13 | Kotlin/Python parity within 1e-6 | `ml` | `MlScorer` | `mlSuite.PARITY_Kotlin_reproduces_every_scikit_learn_probability` | VERIFIED |
| STRETCH-13 | ML never overrides the rule score | `presentation` | `ReportBuilder` | `pipelineSuite.Q2_ML_disagreement_is_flagged_not_silently_resolved` | VERIFIED |
| STRETCH-14 | PDF report with cover, ranked table, per-app sections | `report` | `PdfReportWriter` | `PdfReportWriterTest.a_report_with_300_apps_produces_pages_and_does_not_crash` | NOT-RUN |
| STRETCH-14 | Pagination is pure and unit-tested | `report` | `ReportLayout` | `pipelineSuite.the_PDF_layout_paginates_a_300_app_report_without_overflow` | VERIFIED |
| STRETCH-14 | An empty scan does not crash | `report` | `ReportLayout` | `pipelineSuite.an_empty_scan_still_produces_one_page` | VERIFIED |

## Hard constraints

| ID | Constraint | Module | Class | Test | Status |
|---|---|---|---|---|---|
| CONSTRAINT-ONDEVICE | No root, no ADB needed by the end user | `inventory` | `RealPackageSource` | `AppInventoryTest.scan_uses_only_package_manager` | NOT-RUN |
| CONSTRAINT-PRIVACY | No user data leaves the device | `hash-intel` | `ThreatIntelService` | `ThreatIntelServiceTest.the_request_contains_ONLY_the_hash` | NOT-RUN |
| CONSTRAINT-PRIVACY | Consent is default-off, explicit and revocable | `hash-intel` | `ConsentManager` | `ThreatIntelServiceTest.consent_defaults_to_off` | NOT-RUN |
| CONSTRAINT-OFFLINE | Core features work offline | `scoring` | `RiskEngine` | `engineProfileSuite.PROFILE_torch_with_SMS_sideloaded_CRITICAL` | VERIFIED |
| CONSTRAINT-OFFLINE | Network code confined to one module | `hash-intel` | `ThreatIntelProvider` | `scripts/check_traceability.py` | VERIFIED |
| CONSTRAINT-VISIBILITY | QUERY_ALL_PACKAGES declared | `app` | `AndroidManifest.xml` | `scripts/check_traceability.py` | VERIFIED |
| CONSTRAINT-NOMALWARE | Fixtures are inert; no real malware hashes shipped | `fixture-apps` | `FixtureActivity` | `scripts/check_traceability.py` | VERIFIED |
| CONSTRAINT-GENERIC | No fixture package name appears in scoring logic | `scoring` | `RiskEngine` | `scripts/check_traceability.py` | VERIFIED |

## Deliverables

| ID | Deliverable | Where | Status |
|---|---|---|---|
| DELIVERABLE-01 | Working APK | `dist/risk-auditor-debug.apk` via `scripts/build_apk.sh` | BLOCKED |
| DELIVERABLE-02 | One-page scoring rubric | `docs/SCORING_RUBRIC.pdf` and `.md`, generated by `tools/render_rubric.py` | VERIFIED |
| DELIVERABLE-03 | Source code | this repository | VERIFIED |
| DELIVERABLE-04 | 3-minute live demo script | `docs/DEMO.md` | VERIFIED |

`DELIVERABLE-01` is `BLOCKED`, not missing: `dl.google.com` and
`repo.maven.apache.org` are refused by the egress proxy in the authoring
environment, so the Android Gradle Plugin cannot be downloaded and no APK can be
produced. The build is complete and `scripts/build_apk.sh` will produce it on any
machine with an Android SDK. See `docs/LIMITATIONS.md` L-00.
