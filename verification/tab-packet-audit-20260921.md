# TAB packet audit — 2026-09-21

Base: `6cb9ec191` (includes the current close-range nametag and hitbox fixes).
Upstream inspected: `NEZNAMY/TAB:master`, `174c58b64`.

## Changes

- `SafeScoreboard`: unchanged cached objective titles, render types and number formats no longer produce platform update calls. All team-update overloads similarly skip unchanged fields. Explicit `resend()` still recreates client state; frozen updates remain stored for replay. Display-slot restoration was deliberately retained because another plugin can take over the sidebar.
- `LongLine`: score/number-format changes and prefix/suffix changes retain the score entry and team when the entry name is unchanged. Previously the score and team were removed and created again on every such refresh. Changing the entry name still removes the old entry and recreates team membership. Empty lines still disappear and reappear correctly.
- `ChannelPacketQueue`: keep the scheduled flag set throughout draining, avoiding redundant queued drain tasks when sends occur during writes. Recheck the queue after clearing the flag so a late packet is not stranded. Release reference-counted packets discarded after channel closure or event-loop rejection.
- `Condition`: port upstream `f2ea34a71` null guard for header/footer config conversion before the placeholder manager is initialized.

## Upstream selection

Inspected all 14 upstream-only commits. The config-conversion fix is applicable and was ported. The others concern Minecraft 26.3 support (Paper, Spigot, Fabric, NeoForge), dropping old Fabric versions, bStats, badges, Gradle/version bumps and deprecation of a redundant API method. They do not fix the 1.8 packet paths here and were not merged into this slim Bukkit/Velocity fork.

## Additional review

- Header/footer already compares the rendered strings before sending.
- Player-list formatting already checks changed properties during ordinary refresh; forced refresh and join restoration are necessary.
- Multi-line nametags already compare snapshots and individual text lines; movement replication and range-crossing updates remain intact.
- The 1.8 team adapter already compares serialized prefix/suffix/flags, so the new shared team guards also avoid conversion work before that adapter.
- Live config gives TAB the lobby sidebar; Bolt handles match/party/queue sidebars. Disabled player-list objective, below-name, bossbar and layout features were not deleted.
- Existing placeholder-error log entries inspected were dated June 24 and originated in Bolt's FFA placeholder. They are not evidence of a current TAB error.

## Validation

Amazon Corretto 25.0.4, repository Java 25 toolchain (production release target remains unchanged).

Commands:

```text
gradlew.bat :shared:test :bukkit:v1_8_R3:shadowJar :velocity:shadowJar --offline
gradlew.bat :shared:test --offline
git diff --check
```

Final shared test suite: 16 tests, zero failures/errors. Covers unchanged updates, individual changed fields, frozen state plus explicit resend, score-only and suffix-only changes, renamed and empty/restored lines, config conversion, queue order/batching, reentrant and late sends, rejection cleanup, and existing layout tests.

Compared every built `me/neznamy/tab/*.class` entry against the downloaded live jar. Only `LongLine`, `Condition`, `ChannelPacketQueue`, `SafeScoreboard` and its nested classes differ. The current nametag renderer is byte-for-byte preserved. Test dependencies are test-only.

Artifact: `bukkit/v1_8_R3/build/libs/TAB-v1_8_R3-6.2.0-SNAPSHOT.jar`

SHA-256: `578ca4f70b038b057fe2c097c8cb6725eb573722624d1ef6a061ec4e2e2d8b8f`

Live pre-update jar SHA-256: `0ceac3f0509819cd41c424d9890ef8384f665fed7e8eceb78d1873a64e8c9d59`.

No live before/after packet profile or client load test has been performed. Tests demonstrate the specific redundant work removed, not a guarantee that every possible TAB issue is eliminated.
