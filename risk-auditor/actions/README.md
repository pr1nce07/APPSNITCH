# :actions (M14)

*Android*

Turns remediation steps into Android intents (PS-01 must-have 06).

## Public API
`IntentFactory.resolve(action, packageName)` → `ResolvedAction`, plus
`resolveAll` and `appDetailsIntent`.

## Why every intent is resolved before being offered
OEMs remove and rename Settings screens. `ACTION_MANAGE_OVERLAY_PERMISSION` is
missing on some builds; all-files-access only exists from API 30. Firing an
unresolvable intent throws `ActivityNotFoundException`, which to a user looks
like this app is broken.

So each intent is resolved first, and anything unresolvable falls back to the
app-info screen — present on every Android device ever shipped — with the reason
recorded so the UI can say "open this manually".

The *ordering* of the steps lives in `:presentation`'s `RemediationAdvisor`;
this module only builds the intents.

## Tests
```bash
./gradlew :actions:testDebugUnitTest
```
