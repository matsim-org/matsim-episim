# Cologne — Influenza 2025/26

## Run

```
java -jar matsim-episim.jar --modules org.matsim.episim.run.modules.InfluenzaCologneScenario
```

or `org.matsim.episim.run.batch.StarterBatchInfluenzaCologne` for a seeded batch that is packed for the
viewer and, if `EPISIM_OUTPUT` is a versioned directory of an SVN working copy, uploaded there.

The run covers the 2025/26 influenza season: Monday of KW 39/2025 (2025-09-22) to Sunday of KW 20/2026
(2026-05-17), i.e. one week before the RKI season (KW 40–KW 20) through its end. It uses the Senozon
Cologne population with its ordinary activity pattern: no pandemic mobility reductions, no masks, no
tracing, and no COVID household-susceptibility calibration.

`InfluenzaCologneScenario`
(`src/main/java/org/matsim/episim/run/modules/InfluenzaCologneScenario.java`) is a thin module: it
loads `config.xml` as-is and builds the contact-restriction **policy** in code (`buildPolicy()`). It
also binds the Cologne-specific contact/infection models the parameterisation doc assumes.

Two different sources for two different things:

- **Policy** stays in code, in `buildPolicy()`: the NRW school holidays 2025/26 (KMK, "Ferien im
  Schuljahr 2025/2026"). Everything else is open.
- **Everything else is real, loaded data** in `config.xml` / `progression.conf`: dates, calibration
  parameter, contact intensities, virus-strain/pathogen parameters including the infectivity profile
  (`infectivityProfile` of the influenza `pathogenParams`), import schedule, tracing, and
  disease-progression timing (the XML references `progression.conf`, which loads automatically). Edit
  either file directly for a parameter sweep, no rebuild needed.

Both trace back to [`docs/influenza-parameterisation.md`](../../docs/influenza-parameterisation.md),
which was written for 2022/23. Still carried over from 2022/23 as placeholders: the calibration target
of `infectiousness` and the pooled-strain choice. The infectivity profile is the former built-in COVID
curve, now explicit and read with a signed offset (negative before symptom onset).

The influenza **import** follows the weekly laboratory-confirmed notifications of Nordrhein-Westfalen
2025/26 (`nrw-ifsg-influenza-2025-26.tsv`, RKI Open Data, CC BY 4.0, pinned commit in the file header):
centred three-week mean, spread over the days of each reporting week, fractional agents carried over
from day to day. Only the level (`IMPORT_PER_NRW_WEEKLY_CASE` in `GenerateInfluenzaConfig`) is free; it
is a calibration prior kept small so the model does not reproduce the NRW curve through its input —
check the imported share at the peak in `diseaseImport.tsv`.

## Regenerating

Run `org.matsim.episim.run.scenarios.cologne.GenerateInfluenzaConfig#main` from the repo root after
changing the Cologne base setup or the influenza parameterisation logic; it overwrites both
`config.xml` and `progression.conf` (and calls `InfluenzaCologneScenario#buildPolicy()` so the
snapshot's policy matches what actually runs, even though `config.xml`'s `policyConfig` param itself
is inert — a policy built in Java has no file behind it, so it can't be read back; see
`InfluenzaCologneScenario`'s javadoc).
