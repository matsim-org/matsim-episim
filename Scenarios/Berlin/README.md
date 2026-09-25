# Berlin — Influenza 2025/26

## Run

```
java -cp matsim-episim.jar org.matsim.episim.run.batch.RunInfluenza --scenario Scenarios/Berlin
```

from the repo root (paths in `config.xml` are relative to it). `RunInfluenza` runs two seeds of the whole season,
packs them for the viewer and, if `EPISIM_OUTPUT` is a versioned directory of an SVN working copy, uploads the
packages there. The runtime module alone is `org.matsim.episim.run.modules.InfluenzaScenario` with
`-Depisim.scenario=Scenarios/Berlin`.

The season is 2025-09-22 to 2026-05-17, as for Cologne. Population and events are the public Senozon open-data
release of Berlin (25 % sample, `openDataModel/berlin/input` on the VSP SVN), read directly from the SVN.

## Files

- `config.xml`, `progression.conf`, `policy.conf` — written by
  `org.matsim.episim.run.scenarios.berlin.GenerateInfluenzaConfig`; everything is data, including the policy (the
  Berlin school holidays 2025/26). Edit them directly for a parameter sweep, re-run the generator after changing the
  base setup or the influenza parameterisation (`InfluenzaParameterisation`).
- `scenario.yaml` — city, region and the observed data the viewer shows next to the model.
- `berlin-ifsg-influenza-2025-26.tsv` — weekly laboratory-confirmed influenza notifications, Berlin (RKI, CC BY 4.0);
  shapes the import and is shown in the viewer.
- `germany-icosari-influenza-sari-2025-26.tsv` — national influenza SARI hospitalisation incidence (RKI ICOSARI), a
  copy of the Cologne file; order of magnitude only.
- `berlin-weather-2025-26.csv`, `berlin-weather-avg-1996-2025.csv` — weather at Tempelhof (Open-Meteo, ERA5) for the
  leisure outdoor fraction.

Base setup, as in the VSP Berlin production scenario: calibration parameter 1.6e-5, Berlin contact intensities, only
leisure seasonal. Not calibrated for influenza yet: `infectiousness` is the 1.0 placeholder.
