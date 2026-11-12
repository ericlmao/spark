# Automatic profilers

spark can start profiler sessions on its own, so that you have profiles from times when nobody was watching. There are two triggers, and both are off by default:

- **Scheduled profilers** start at random times through the day, inside the time windows you configure, plus any fixed times you list. The random times are planned once per day and logged at startup and at midnight.
- **Lag profilers** start when the server lags: when TPS stays below a threshold for a while, or when one or more ticks take longer than a set time.

Both take profiler arguments in the same syntax as `/spark profiler start`, for example `--thread * --timeout 300`. Every automatic session has a timeout; if you leave it out, spark adds the default (300 seconds for scheduled sessions, 120 for lag sessions) and says so in the log.

## Configuration

The settings go in spark's `config.json`, in the plugin's data folder.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `scheduledProfiler` | boolean | `false` | Turns scheduled profilers on. |
| `scheduledProfilerArgs` | string | `--timeout 300` | Profiler arguments for scheduled sessions. |
| `scheduledProfilerRandomCount` | int | `3` | How many random sessions to plan per day. `0` runs the fixed times only. |
| `scheduledProfilerWindows` | list of `HH:mm-HH:mm` | `00:00-24:00` | Windows that random sessions are drawn from. A window such as `22:00-02:00` wraps past midnight. |
| `scheduledProfilerTimes` | list of `HH:mm` | empty | Fixed times that always run. |
| `scheduledProfilerMinSpacingMinutes` | int | `60` | Minimum gap between a random session and any other planned session. |
| `lagProfiler` | boolean | `false` | Turns lag profilers on. |
| `lagProfilerArgs` | string | `--timeout 120` | Profiler arguments for lag sessions. |
| `lagProfilerTps` | number | `15` | Start when TPS stays below this. `0` turns the TPS rule off. |
| `lagProfilerSustainSeconds` | int | `20` | How long the lag must last; also the window the long-tick rule counts in. Clamped to 5-600. |
| `lagProfilerLongTickMs` | int | `2000` | A tick at least this long counts as a long tick. `0` turns the long-tick rule off. |
| `lagProfilerLongTickCount` | int | `1` | Start when this many long ticks happen within the sustain window. |
| `lagProfilerCooldownMinutes` | int | `30` | Minimum time between the end of one automatic session and the next lag session. |
| `lagProfilerMaxPerDay` | int | `5` | Most lag sessions per day. `0` is unlimited. |
| `lagProfilerStartupGraceSeconds` | int | `120` | Time after the first tick during which lag is ignored, so startup does not trigger. |
| `lagProfilerUploadBackground` | boolean | `true` | When a lag session replaces the background profiler, upload the background profiler's data instead of discarding it. |
| `autoProfilerTimezone` | zone id | empty | Time zone for windows and fixed times, such as `Europe/London`. Empty uses the system zone. |
| `autoProfilerBroadcast` | boolean | `true` | Tell online players with the `spark` or `spark.profiler` permission when a session starts and where its result is. |

Example:

```json
{
  "scheduledProfiler": true,
  "scheduledProfilerArgs": "--thread * --timeout 300",
  "scheduledProfilerRandomCount": 4,
  "scheduledProfilerWindows": ["10:00-14:00", "17:00-23:30"],
  "scheduledProfilerTimes": ["04:00"],
  "scheduledProfilerMinSpacingMinutes": 45,
  "autoProfilerTimezone": "Europe/London",
  "lagProfiler": true,
  "lagProfilerArgs": "--only-ticks-over 100 --timeout 180",
  "lagProfilerTps": 16,
  "lagProfilerLongTickMs": 1000
}
```

Invalid values are logged as warnings at startup. Invalid profiler arguments turn off only the trigger they belong to, and the warning names the key.

### Overriding settings without editing the file

Any key can also be set with a system property, `-Dspark.<key>=<value>`, or an environment variable, `SPARK_<KEY UPPERCASED>`. For example `-Dspark.lagProfiler=true` or `SPARK_LAGPROFILER=true`. For plain values a system property wins over an environment variable, which wins over the file. List keys can be given as a comma separated string in any of the three places (`"10:00-14:00,17:00-23:30"`), but a JSON array in the file wins over a system property or environment variable.

## Where the results go

When a session ends, spark uploads the profile and logs a line with the viewer link. If the upload fails, or the arguments include `--save-to-file`, the profile is saved in the plugin folder instead and the log line has the file path. Saved files are named `profile-scheduled-<date>.sparkprofile`, `profile-lag-<date>.sparkprofile` and `profile-before-lag-<date>.sparkprofile`.

Each result is also added to spark's activity log (`/spark activity`) as "Profiler (scheduled)", "Profiler (lag)" or "Profiler (before lag)". The profile's comment says why the session started, for example the measured TPS.

## Caveats

- A lag profile starts after lag is detected, so it captures what follows. Sustained or recurring lag is captured; a single one-off spike is not. With the background profiler on, the "before lag" upload is what contains the onset.
- A fully frozen server thread fires no tick callbacks. A freeze is detected by the long-tick rule once the tick ends, which needs a platform that reports tick durations: Paper, Fabric, Forge or NeoForge.
- `--only-ticks-over <ms>` in `lagProfilerArgs` is useful for intermittent spikes, but gives an empty profile if no tick exceeds the threshold during the session.
- A scheduled profiler replaces a running background profiler and discards its data, the same as a manual `/spark profiler start`. The background profiler restarts when the session ends.
- If a person is already running a profiler, the automatic one is skipped. If a person stops or cancels an automatic session, spark notices on its next check and does not restart anything.
- `/tick rate` below 20 reads as low TPS and will trigger the lag profiler. The cooldown and daily cap bound how often.
- A session in progress at shutdown is lost. Viewer links expire with the upload service; saved files are never pruned.
- Paper bundles its own spark. Check that this build is the active spark on the server.
- Proxies (Velocity, BungeeCord) and the standalone agent have no tick data. Scheduled profilers work there; lag profilers do not.
