# AC30 runtime UI acceptance slice

Verdict: **PARTIAL overall / PASS for the assigned bounded slice**.

This run is bound to candidate tree `e6f9fe69b2a5af6bf59177df1951973c8b018e4f`. The six relevant files in the live Vite runtime copy match their candidate-tree SHA-256 values; details are in `source-identity.json`. The browser session used the existing normal login. No auth storage was injected, no service was restarted, and no repository, index, or parent acceptance artifact was changed.

Runtime identity correction: the first version of this report selected `server-cc3fc6af...jar` by inspecting similarly named files in the runtime directory. That file is a backup. The corrected check resolved PID `45303`, the sole listener on TCP 61806, extracted only its JAR path without retaining its raw command, and proved the live artifact is `server.jar` with SHA-256 `fc3217668c57b99b191b456120a968607c0bbc71b9f6f9fee613735e5d67a377`.

## Passed

- Actual cancelled task `aa73f52f-b318-44b2-921a-4a7182e2e484` displays `已停止接收` and the exact warning: `任务已停止接收结果，模型服务是否已计费无法确认；如需继续，请人工重新执行。` This was observed at 1440x900 and 390x844.
- Its execution record is read-only before refresh, after refresh, and after `/chat -> /presales -> browser back`. Four captured status reads returned HTTP 200 with `readOnly=true` and `streamStatus=idle`.
- The persisted transcript read back a completed 23-character user message and an interrupted 272-character assistant message. Both were visibly rendered in desktop and narrow screenshots; the narrow UI also displayed the interrupted label.
- At 390x844, project and chat document `scrollWidth` both equal 390. Project-to-chat and chat-to-exact-project navigation retained the cancelled status.
- Actual completed S1 task `cb9b7a49-446e-4aea-9ffc-8610dbebb74d` exposes `查看并修订建议`. The review dialog preserves the candidate title, its simulated-content boundary, and `AI 建议` origin on both desktop and narrow layouts.
- Synthetic browser QA then edited this candidate and saved it through the real UI. The one write was `SAVE_CONTEXT` at expected project version 12. The server returned project version 13 and context `65134f91-a37b-49dd-947d-5abb5be76a40` with item version 1, `authority=UNTRUSTED_DRAFT`, `originKind=AI_SUGGESTION`, author and creation fields. Refresh read back the same fields and text.
- The save did not approve or confirm anything: candidate `needsHumanReview` stayed `true`; project stage/status stayed `DISCOVERY`/`ACTIVE`; baselines, reviews, releases, requirements and solutions stayed at zero; the saved context contained no approval or publication field; the UI still showed `尚未确认`.
- The readonly/preview cases observed zero non-GET `/api/v1/presales` requests. The save case observed exactly the authorized commands POST. All browser cases observed zero console errors.

## Not run

- Presentation preview was unreachable because the existing actual S1 output contains no presentation or slide artifact.
- Real model quality, customer acceptance, and publication are outside this evidence. The provider is a deterministic `SIMULATED_MODEL` fixture.
- Full AC30 remains incomplete. This result covers only transcript refresh/read-only behavior, cancellation messaging, navigation, narrow layout, and candidate review reachability.

## Evidence

- `python3 run-case.py desktop-runtime-ui mateclaw-ac30-transcript` — PASS; see `evidence/desktop-runtime-ui.log`.
- `python3 run-case.py narrow-runtime-ui mateclaw-ac30-transcript` — PASS; see `evidence/narrow-runtime-ui.log`.
- `python3 run-case.py narrow-transcript-readback mateclaw-ac30-transcript` — PASS; focused visual readback, see `evidence/narrow-transcript-readback.log`.
- `python3 run-case.py candidate-edit-save-readback mateclaw-ac30-transcript` — PASS; one authorized `SAVE_CONTEXT` write plus refresh readback, see `evidence/candidate-edit-save-readback.log`.
- `python3 verify-source-identity.py` — PASS; resolves the live listener PID and verifies `server.jar` plus six runtime UI files.
- Screenshots are under `evidence/`; scripts are under `cases/`.

## Source mapping

- `PresalesOverview.vue` candidate lines 45-144 render task status, execution navigation, cancellation warning, candidate review, and presentation preview only when presentation output exists.
- `messages.ts` candidate lines 23-24 define the unknown-billing cancellation text.
- `PresalesWorkbench.vue` candidate lines 746-748 route an S1 suggestion to the context review editor.
- `PresalesDiscoveryFields.vue` candidate lines 204-217 label the candidate as context that is not accepted fact.
- `PresalesService.java` candidate lines 391-394 force every `SAVE_CONTEXT` item to `authority=UNTRUSTED_DRAFT`; baseline and release approval are separate gated commands.
- `PresalesProjectItems.java` candidate lines 59-79 assign item identity, revision, author and creation time without approval state.
- `ChatConsole.vue` candidate lines 198-213 mark transcript messages read-only and lines 336-342 disable the composer when the server-owned transcript guard is read-only.
