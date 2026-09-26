# Cologne — Influenza 2025/26

## Run

```
java -cp matsim-episim.jar org.matsim.episim.run.batch.RunInfluenza --scenario Scenarios/Cologne
```

from the repo root (paths in `config.xml` are relative to it). `RunInfluenza` runs two seeds of the whole season,
packs them for the viewer and, if `EPISIM_OUTPUT` is a versioned directory of an SVN working copy, uploads the
packages there. `--infectiousness 0.3,0.35` runs those values instead of the one in `config.xml`, `--seeds N` the
first N seeds. The runtime module alone is `org.matsim.episim.run.modules.InfluenzaScenario` with
`-Depisim.scenario=Scenarios/Cologne`.

The run covers the 2025/26 influenza season: Monday of KW 39/2025 (2025-09-22) to Sunday of KW 20/2026
(2026-05-17), i.e. one week before the RKI season (KW 40–KW 20) through its end. It uses the Senozon
Cologne population with its ordinary activity pattern: no pandemic mobility reductions, no masks, no
tracing, and no COVID household-susceptibility calibration.

Everything about the scenario is data: `config.xml`, `progression.conf` and `policy.conf` (the NRW school holidays
2025/26, KMK "Ferien im Schuljahr 2025/2026"), plus `scenario.yaml` for the city, region, observed data and weather
files. Dates, calibration parameter, contact intensities, virus-strain/pathogen parameters including the infectivity
profile (`infectivityProfile` of the influenza `pathogenParams`), import schedule, tracing and disease-progression
timing can be edited directly in these files for a parameter sweep, no rebuild needed. The model bindings and vehicle
capacities are those of every influenza scenario, see `InfluenzaScenario`.

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
changing the Cologne base setup or the influenza parameterisation (`InfluenzaParameterisation`); it overwrites
`config.xml`, `progression.conf` and `policy.conf`.
