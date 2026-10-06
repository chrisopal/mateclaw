# Parallel delegation event fixture repair

Base HEAD 6f85271c7ece1812800a4a14696b9c69788df757, tree320bcb46114ba0ed14d99c93101f685d6676cc80; clean before repair. Capacity commit gate cyxm2o18 passed; normal push gate awvoz5n2 rejected one Java error, target unchanged and no push occurred.

Observed root cause: Mockito @InjectMocks does not apply Spring @Value. DelegateEventSequenceTest left parallelTimeoutSeconds at0 while production annotation defaults300. Push log records parent-parallel-456 then "Parallel delegation timed out (0s)"; both chat stubs were unused. Other DelegateAgentToolTest explicitly sets a3second unit-test budget. Event test asserted agent names only, so timeout results could satisfy its business assertions.

Scope: test fixture only. Explicitly set bounded parallel wait following existing test pattern; retain strict Mockito and event assertions. Strengthen success-path assertions to require ResultA/ResultB and summary success2/timeout0. No production, gate, dependency or skip changes. Existing timeout/cancellation suite must still pass.

Verification: preserve failed full gate; first add strengthened assertions and a short child scheduling delay to expose the zero-budget path in an isolated RED run, then configure the fixture wait and rerun event plus delegate/timeout/cancellation tests. Remove temporary diagnostic delay after demonstrated failure; no weakening of result assertions. Independently review test changes, dev and normal commit/push gates on exact tree. Full UI/Java pass remains required by existing gate; do not retry unchanged full gate merely to obtain green.
