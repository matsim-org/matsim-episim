# Berlin multi-season influenza: data, 2022/23 to 2025/26

The Berlin counterpart of `Scenarios/Cologne-multiseason` (see its README and [`docs/influenza-multiseasonal.md`](../../docs/influenza-multiseasonal.md),
section 14). The single-season scenario in `Scenarios/Berlin` is unchanged. Horizon: 2022-09-26 to 2026-05-17, four seasons.

| File | Content | Source |
|---|---|---|
| `berlin-ifsg-influenza-2022-26.tsv` | weekly laboratory-confirmed notifications, Berlin, all ages, 2022-W38 to 2026-W21 | Episerve dataset q8399965930733 (RKI IfSG) |
| `germany-icosari-influenza-sari-2022-26.tsv` | weekly influenza SARI hospitalisation incidence, Germany (as for Cologne) | RKI ICOSARI |
| `influenza-sentinel-weekly.csv` | weekly sentinel detections by subtype, Germany (the same file as for Cologne; splits the import between the A strain and B/Victoria) | Episerve dataset q4242040001970 (WHO FluNet) |
| `berlin-weather-2022-26.csv` | daily maximum temperature and precipitation, 2022-09-01 to 2026-05-31 | Open-Meteo Historical Weather API (ERA5), CC BY 4.0 |
| `berlin-school-holidays-2022-26.tsv` | Berlin school holidays, first and last day, with the bridge days | KMK "Ferien im Schuljahr" 2022/23 to 2025/26 (the PDFs are in `raw/`) |
| `population-ages.tsv` | agents per age | the persons file of the Berlin population (microm:modeled:age) |

`raw/` holds the original downloads (Open-Meteo CSV, KMK PDFs). The two IfSG/FluNet tables are built by
`../Cologne-multiseason/build-reference-data.py` from the parquet files there.

## Generating and running

```
java -cp matsim-episim.jar org.matsim.episim.run.scenarios.berlin.GenerateInfluenzaConfig berlin-multiseason
java -cp matsim-episim.jar org.matsim.episim.run.batch.RunInfluenzaGamma --scenario Scenarios/Berlin-multiseason --gamma 0.6,0.8,1.0
```

On the cluster, `scripts/influenza.sh run --scenario Scenarios/Berlin-multiseason --gamma 0.6,0.8,1.0 --seeds 2` starts the same batch
in the image (`--iterations 238` runs the first season only). One run needs about 14 GB of Java heap (Berlin, 25 % sample).
