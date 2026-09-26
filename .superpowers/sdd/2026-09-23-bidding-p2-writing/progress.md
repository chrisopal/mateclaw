# SDD ledger — plan: docs/superpowers/plans/2026-09-23-bidding-p2-writing.md

2026-09-26: Start from 8137bd5c, identical to dev/origin/dev. P1 synthetic actual-model/confirmed baseline passed; real-tender/golden, business DB inventory, MySQL/Kingbase NOT_RUN. User says continue; P2 engineering proceeds without claiming these acceptance gaps closed. Existing worktree reused; running preview uses copied DB and explicit enterprise mode. Migration V213 already deployed, new material/handoff migration is V215; task headings normalized for gates. Task 1 pending.

P2 API coordination pending Task2/3: GET outline editExpectedRef; GET writing chapter editExpectedRef and tasks:[{taskId,status,attemptCount}] from authoritative server head/task association. Empty head sentinel is server-issued version 0, not UI-fabricated/project-ref substitution. SAVE_OUTLINE expected outline head, EDIT/ADOPT_CHAPTER expected chapter head. Add these to plan after Task1 commit before dispatch Task2.

Task1 code 993ab1f4: controller ownership gate PASS (13 implementation files), fresh Java21 targeted9 tests PASS. Immutable presales material Ref accepted as in-scope integration detail (same table/owned service). Sol review pending. Task2/3 API notes folded into plan: authoritative empty/current editExpectedRef and server-correlated per-chapter tasks. No runtime or real-tender claim.

Sol Task1 REQUEST_CHANGES: page-type ACL, current employee recheck, controlled repeated receipt. Broad controller regression125:1FAIL migration table-count9vs11. Task1 ownership expanded to migration test for exact inventory update, data-preservation assertions must stay. Fix loop before Task2.

Task5 UI stage: 5files20mounted tests, ESLint/precision/vue-tsc/tempbuild reportedPASS, API/livebrowser/model NOT_RUN. Uncommitted UI preserved, child assignment ended. Ownership expanded explicitly for materials/workbench regressions and generated ElTree declaration; final shape/live integration still pending Task2/3.

Controller UI static re-run:5files20testsPASS /tmp/mateclaw-p2-ui-static-tests.log. Final Task5 integration review must cover block-preserving human edit: current openEditor joins blocks.text and save reparses paragraphs, silently loses list/table/image/heading metadata. Replace with finite block editor or preserve untouched blocks; test edit of mixed heading/paragraph/list/table/image retains structure. Do not claim final UI acceptance until this closes.

Fresh Task1fix ownershipgatePASS125 tests. Sol re-review confirms implementation3fixes, requests actual HTTP/H2 firstreceipt/replay/newop409+rowcounts because existing receipt test mocks count. Ownership expanded BiddingHandoffReceiptTest.java for enabled/mock-origin fixture independent of presales-disabled fixture; nextfix testonly.

Task1 APPROVED by Sol after true HTTP/H2 receipt evidence f08fa124; freshfinalgatePASS126tests0errors. dev FF+push origin/dev=f08fa124, useruntrackedfilespreserved. Task2 Luna p2_outline dispatched BASEf08fa124 frombrief+controllernotes. Task5 mixed-block correction21testsreportPASS, stilluncommitted/liveNOT_RUN. RootmainpackageTask1 inprimarytarget (independentofchildworktreetarget), /tmp/mateclaw-p2-task1-main-package.log; possibleisolatedruntimeupdatependingpackageverification. MainnoP2UI yet.

Task1runtime mainpackageSUCCESS, ownedoldruntime stopped/backedupisolatedDB/newmainjar18118 session25698 V215migrationSUCCESS. Authenticated newbrowser sessionbidding-p2-check profileenterprise, syntheticproject200/CONFIRMEDbaselineV1 afterrestart/materials200empty. ActualP2flowNOT_RUN; oldbrowserdraftleftintact. See runtime-task1.md.

Task3/5 pending integration: UI saveEdit currently spreads original payload plus nested chapter/blocks; final real API must send whitelisted EDIT fields while preserving content/evidence, not task/status/schema/originmetadata. Task3 head0 missingpreviousRef requires explicit task-target head guard even when absent previous chapter, so initial candidate becomesSTALE afterhumanedit; never put version0 sentinel into genericDependencies.valid refs. Task3 may need explicit Dependencies ownership added beforedispatch for chapter/manuscript currentness. Runtime skillinstaller uses POST /api/v1/skills configJson.skillDir (approved isolatedroot/1/package); bundledsync auto copies files but doesnotcreate missingDBskill row. Actualmodel setup deferreduntil2/3approved.

Root continuation 2026-09-26: Task2 BASE updated36401b51 to declare scoped Repository recursive ref APIs. Task3 Dependency/Repository ownership and explicit initial head guard added. Root independently UI stage21testsPASS /tmp/mateclaw-p2-ui-stage21-tests.log, final API/browser remainsNOT_RUN. Task2 self-check sent: incomplete mappings are saveable candidates, confirm enforces coverage; first employee-only candidate uses empty edithead for confirmation.

Task2 commitb5f68272; root independent plan-task-gatePASS15files /22tests0failureerror, scoped Sol p2_outline_review dispatched review36401b51..b5f68272.diff. Do not advanceTask3 untilreviewapproved. Created isolatedwriterid2103866314894630914 deepseek-chat readback200; not yetskillsbound/projectassigned/modelrun.

Task2 Sol reviewREQUEST_CHANGES: dispatchreadOnlywrites; legacyfree-textdecisionreasonGET500; emptyoutlineallowed; malformedSAVEpayload/types500/accepted. Fixround1dispatchedsameLuna p2_outline BASEb5f68272, coversOutlineTest/ValidatorTest +combined22existing. NoadvanceTask3/MainFF yet. JWTfixturewarningrecorded, testsnotpristinelog.

Task2 fixround1 commit6bdcf1b8 addresses4findings; rootfreshgatePASS15files/24tests0errors. ScopedSolp2_outline_review re-reviewdispatched b5f68272..6bdcf1b8, beforeTask3.

Task 2: fix round 1/5 (4addressed,0open; b5f68272..6bdcf1b8).
Task 2: complete (commits36401b51..6bdcf1b8, SolspecPASS/qualityAPPROVED; root24testsPASS). devFF6bdcf1b8; pushinprogress. Task3BASE6bdcf1b8.

Task3 gate PASS HEAD5d3793c3 32tests; Sol REQUEST CHANGES on replacement self-staleness, HUMAN_EDIT assembly, image classification, full-envelope size and workflow test coverage. Luna fix in progress, no main merge yet. Task5 Luna matching approved Task2/Task3 contracts in separate UI ownership; impact integration deferred until Task4 API.

Task3 fix07c26d51 rootgatePASS36tests, Sol scoped re-review APPROVED all5findings. CandidateheadGuard publicserverfield coordinated with Task5 UI. UI contract/lifecycle/securitystage29focusedtestsPASS incl saveacknewref,409draftretention,403/404clearing, stringguardversions; stilluncommitted/notfinalTask5. Task4 next aftercontrollerplanboundaryamendment.
