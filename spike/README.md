# spike/ — Stage 1 evidence (frozen)

Throwaway code from the Stage 1 spike. It is **not part of the build** (not listed in `settings.gradle.kts`) and is kept only as evidence for the spike findings.

| File | What it shows |
|---|---|
| `src/test/java/.../MockServerCapabilitiesTest.java` | MockServer features the kit relies on: canned responses, 429 + `Retry-After`, delays, sequences, last-message JSONPath matching, recorded timestamps, dropped connections |
| `run-sut.sh` | Manual run of PetClinic AI against MockServer (`up [h2\|postgres]`, `down`) |
| `probes.py` | Runtime probes of the SUT's failure behaviour (429, 503, malformed JSON, unknown tool, tool loops) |

Do not build on this code. The reusable versions live in `kit-*` and `examples/petclinic-ai`.

To re-run the capability test once: temporarily add `"spike"` to `include(...)` in `settings.gradle.kts`, run `./gradlew :spike:test`, then remove it again.
