# Independent verification (2026-09-17)

Parent reran `python3 scripts/build.py` and `python3 tests/smoke.py`: both exit 0. Real Mindustry 160.4 headless loader initialized the mod and answered sc-status, features=false. Rebuilt JAR SHA256: f0daf503cc9f1f9f3b2e26b0ac8c8a4f5a2f297b9aa643e0b8873637b3a68897. JAR contains bootstrap only, NOT SharedCampaignState.

`fixture.SharedStateTest`: RED failed for missing SharedCampaignState, GREEN passed 9 assertions after implementation. This is an in-memory shared balance/research-name experiment with trusted synthetic costs, not vanilla campaign resources, actual research, persistence, network or two ticking sectors. Two views reference one shared Java object. It must NOT be advertised as cross-world synchronization.

`fixture.EngineIsolationProbe`: exit 0. Distinct World/GameState objects retain distinct dimensions, wave counters and rules flags. Assigning Vars.world/state replaces the global active references. This is a constructors/fields test only; no map generation or simulation.

Remaining: two independent running engines and real cross-process protocol, host-authoritative per-sector inventory and research, save integrity/reconnection, guest planet UI, desktop GUI testing, public source publication beyond initial README. No user saves or production services changed. Android deferred by user. No playable shared-campaign release exists.
