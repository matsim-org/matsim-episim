# Cologne multi-season influenza: data, 2022/23 to 2025/26

Input data for the multi-season scenario described in [`docs/influenza-multiseasonal.md`](../../docs/influenza-multiseasonal.md).
The single-season scenario in `Scenarios/Cologne` is unchanged; where the files overlap with its files they are identical.

Horizon: 2022-09-26 (Monday of KW39/2022) to 2026-05-17 (Sunday of KW20/2026), four seasons.

| File | Content | Source |
|---|---|---|
| `nrw-ifsg-influenza-2022-26.tsv` | weekly laboratory-confirmed notifications, Nordrhein-Westfalen, all ages, 2022-W38 to 2026-W21 | RKI, "Laborbestätigte Influenzafälle in Deutschland", CC BY 4.0, commit pinned in the header |
| `germany-icosari-influenza-sari-2022-26.tsv` | weekly influenza SARI hospitalisation incidence, Germany, all ages, same weeks | RKI, "SARI-Hospitalisierungsinzidenz", CC BY 4.0, commit pinned in the header |
| `cologne-weather-2022-26.csv` | daily maximum temperature and precipitation, 2022-09-01 to 2026-05-31 | Open-Meteo Historical Weather API (ERA5), CC BY 4.0 |
| `nrw-school-holidays-2022-26.tsv` | NRW school holidays, first and last day | KMK "Ferien im Schuljahr" 2022/23 to 2025/26 |
| `influenza-sentinel-weekly.csv` | weekly detections of A(H3N2), A(H1N1)pdm09 and B/Victoria of the AGI practice sentinel, Germany; gives the split of the import between the A strain and B/Victoria | RKI ARE-Wochenbericht, tables of the virological sentinel (see the header of the file) |

Notes:

- The notifications are testing-driven and only give the **shape** of the import; ICOSARI is national and an
  order-of-magnitude check. Neither is a Cologne series.
- The weather is a gridded reanalysis, not a station series.
- The flexible school holiday days of individual schools are not in the holiday file.
- The 1996 to 2025 average weather file of `Scenarios/Cologne` is still the fallback of the weather helper for days after
  the last date of the weather file.

## Generating and running

The config, the progression and the policy are written by the generator, from the repo root:

```
java -cp matsim-episim.jar org.matsim.episim.run.scenarios.cologne.GenerateInfluenzaConfig cologne-multiseason
```

Run the four seasons in one run (1,330 iterations, 2022-09-26 to 2026-05-17) with the batch of the common factor γ:

```
java -cp matsim-episim.jar org.matsim.episim.run.batch.RunInfluenzaGamma --scenario Scenarios/Cologne-multiseason
```

`--gamma 0.8,1.0` runs those values instead of γ = 1, `--seeds N` the first N seeds. All five strains have the
infectiousness 0.30 of the config; γ multiplies the calibration parameter and so scales the transmissibility of all strains
alike. Snapshots are written every 100 iterations (iterations 300, 700 and 1000 lie in the summers).

