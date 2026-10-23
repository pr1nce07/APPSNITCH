# :store (M12)

*pure Kotlin*

Snapshot persistence, so the next scan can be diffed against the last.

## Public API
| Type | Role |
|---|---|
| `SnapshotStore` | `get`, `all`, `put`, `putAll`, `remove`, `retainOnly`, `isEmpty`, `clear` |
| `InMemorySnapshotStore` | reference implementation; what tests use |
| `JsonFileSnapshotStore` | single JSON file, atomic rename on write |

## Design notes
JSON file rather than Room: the data is a small map read once and written once
per scan, so a database buys a schema and a migration path and costs this module
its purity (`docs/ASSUMPTIONS.md` A-13).

**Failure policy:** a missing, unreadable or corrupt file is treated as an empty
store and reported through the `onError` callback. Losing the previous scan
means the next one re-baselines — mildly annoying. Throwing would abort the
scan, which is much worse.

Writes go to a temp file and are renamed into place, so a process that dies
mid-write leaves the previous good file intact.

## Tests
```bash
./gradlew :store:test
```
