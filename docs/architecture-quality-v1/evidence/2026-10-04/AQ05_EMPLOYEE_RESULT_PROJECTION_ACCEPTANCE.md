# AQ-05 employee-result projection — engineering slice

Status **ENGINEERING_SLICE_VERIFIED_UNSUBMITTED**, formal AQ05/AC acceptance still open. HEAD6ce0cd21592b661b6f79dd1726cf68a9b334e1e2/tree3a16cabe1768953a4887c2613a1238b222815f55; baseorigin/dev ca0ffbf8b95c2aa8bdb2ba3a1b161b261f6e1f93. The candidate is working-tree WIP, not this committed tree. [Results and archived source/log SHA256](employee-result-projection-results.json) identify the actual combined candidate, including prior AQ20 files.

Changes: PresalesService delegates its original skill-to-draft conversion to new package-private PresalesEmployeeResultProjection. It shares the same PresalesSolutionPolicy instance and PresalesProjectItems revision algorithm, and has no repository, runtime or authorization dependencies. Service retains the original accepted-task/authority/CAS/transaction/revision/receipt ordering and identical Rejected-to-legacy HTTP translation. Public constructor/API and serialized/persisted shapes remain unchanged. Service loses70 net lines; no generic strategy registry or callback layer was introduced. The new test covers seven business characterization cases across S1–S8 and unknown skills.

Before production edits the seven characterization tests exercised the original actual Service method through a bounded reflection adapter. They all passed, together with five real-database atomic-acceptance tests and four SolutionPolicy contract tests:16/0/0/0. After extraction, the same adapter exercises the actual Service delegation, including error translation; expectations were retained. Candidate/result deep copies, server provenance, S1/S2 draft authority/confirmation normalization, S3/S4 revision append, S5/S6 aliases/shared source policy, S7 AI review isolation and existing S8/unknown behavior are covered.

Final command with actual JDK21:

```bash
mvn -B -pl mateclaw-server -am \
  '-Dtest=Presales*Test,ProjectAuthorityFenceDatabaseTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.proc=full test
mvn -B -pl mateclaw-server -Dquality.base=origin/dev spotless:check
python3 -B scripts/quality/verify.py --mode dev --base origin/dev
```

Final tests actualexit0:339 total, **338 executed**,0 failures/errors,1 existing conditional PPT Skill skip. Projection7, authority-database19, atomic-acceptance5 and preceding hash/storage25 all actually executed. Source/permission/repair/CAS/replay/archive/publication integration tests remain present. The Flyway ERROR in the log is expected fault injection in flywayRollsBackTheWholeJavaBackfillWhenALaterRowCannotBeWritten; its assertThrows and complete test suite pass. It is not a failed application migration.

Initial devqgi_0_b4 and source-slice devlknj0cdt actualexit0/SCAN_PASS; application toolsNOT_RUN by quick-scan definition and submission_ready=false. Final source/doc dev kt6i6pia actualexit0/SCAN_PASS; report and raw log retained in the index. Real project Spotless final check actualexit0; its111 candidate files were clean by cached content checks. An initial check correctly rejected independent Google Main formatting differences. A first attempt at scoped apply used glob syntax for a regex property and failed; the corrected expression matched only the six owned Java files. Actual project formatter changed five and cached one. Failures and exact corrective commands/logs are retained; no plugin/tool version, rule, baseline, assertion or skip was changed. Tests were rerun after the final project formatting. The earlier AQ20 document now corrects its explanation of the initial no-op and distinguishes prior snapshot hashes from current candidate identity.

Independent bounded review /root/authority_writer_audit compared published HEAD with the current move:COMMENT/no substantive blocker, original policy/revision/error behavior equivalent and task insertion/projection still at the same transaction position. JavaLSP NOT_RUN; actual javac/JDK21 and tests provide compilation evidence. Unit characterization does not prove real actor authorization by itself; the separate19+5 database tests and authenticated integration cases supply their own bounded evidence. No actual MySQL authority-concurrency or customer/model claim is made.

Hash/replay/receipt methods independently remain byte-identical to HEAD, with hashes recorded in results; no reference to PresalesRequestHashV2 exists in the application writer. All five frozen V218/V1 source hashes remain exact. The preceding V219 storage migration is still only an uncommitted working proposal. No edit to existing migration, new dependency, production data, remote administration or deployment.

Remaining: complete typed domain DTO/runtime/state/error and use-case boundaries, full V2 object/revision/dependency migration and authoritative missing schema, legacy receipt policy/new hash integration/HTTP409/concurrent writer evidence, real Kingbase/full install/production recovery/Office/model/role-browser/customer QA, maintainer approval and remote required CI. Exact staged commit gate, checked push and PR read-back are NOT_RUN for this combined WIP. Do not submit a half-integrated hash helper as a completed idempotency fix.

Rollback for this responsibility slice only: restore the Service method and remove its new pure projection/test, preserving the separate AQ20 WIP. No persisted business fact conversion is needed. All formal acceptance statuses remain open; the goal has not been marked complete.
