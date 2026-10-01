#!/usr/bin/env python3
"""Builds nrw-ifsg-influenza-2022-26.tsv and influenza-sentinel-weekly.csv from the two Episerve datasets in raw/.

  raw/IfSG_Influenzafaelle.parquet  https://data.episerve.zib.de/dataset/q8399965930733  (RKI IfSG, laboratory-confirmed cases)
  raw/VIW_FNT.parquet               https://data.episerve.zib.de/dataset/q4242040001970  (WHO FluNet, Germany)

Needs pandas and pyarrow. Run in this folder.
"""
import pandas as pd

# ---- IfSG: NRW, all ages, 2022-W38 .. 2026-W21 (the horizon of the scenario plus the weeks for the smoothing)
ifsg = pd.read_parquet("raw/IfSG_Influenzafaelle.parquet")
nrw = ifsg[(ifsg.Region_Id == 5) & (ifsg.Altersgruppe == "00+")].set_index("Meldewoche").Fallzahl
weeks = [w for w in nrw.index if "2022-W38" <= w <= "2026-W21"]
with open("nrw-ifsg-influenza-2022-26.tsv", "w", encoding="utf-8") as out:
    out.write("# Weekly laboratory-confirmed influenza notifications (IfSG), Nordrhein-Westfalen (Region_Id 05), all ages (00+).\n"
              "# Source: Episerve dataset q8399965930733 (https://data.episerve.zib.de/dataset/q8399965930733), file\n"
              "#   IfSG_Influenzafaelle.parquet, from Robert Koch-Institut, \"Laborbestätigte Influenzafälle in Deutschland\", CC BY 4.0.\n"
              "# week = ISO reporting week (Meldewoche: the week the health office learned of the case); cases = Fallzahl.\n"
              "# Drives the shape of the influenza import; weeks 2022-W38 and 2026-W21 only serve the smoothing.\n"
              "# Built by build-reference-data.py; the values equal the earlier extract of the RKI file on GitHub.\n"
              "week\tcases\n")
    for w in weeks:
        out.write(f"{w}\t{nrw[w]}\n")

# ---- IfSG: Berlin, all ages, same weeks, for the Berlin multi-season scenario
berlin = ifsg[(ifsg.Region_Id == 11) & (ifsg.Altersgruppe == "00+")].set_index("Meldewoche").Fallzahl
with open("../Berlin-multiseason/berlin-ifsg-influenza-2022-26.tsv", "w", encoding="utf-8") as out:
    out.write("# Weekly laboratory-confirmed influenza notifications (IfSG), Berlin (Region_Id 11), all ages (00+).\n"
              "# Source: Episerve dataset q8399965930733 (https://data.episerve.zib.de/dataset/q8399965930733), file\n"
              "#   IfSG_Influenzafaelle.parquet, from Robert Koch-Institut, \"Laborbestätigte Influenzafälle in Deutschland\", CC BY 4.0.\n"
              "# week = ISO reporting week (Meldewoche); cases = Fallzahl.\n"
              "# Drives the shape of the influenza import; weeks 2022-W38 and 2026-W21 only serve the smoothing.\n"
              "# Built by ../Cologne-multiseason/build-reference-data.py.\n"
              "week\tcases\n")
    for w in weeks:
        out.write(f"{w}\t{berlin[w]}\n")

# ---- FluNet: sentinel specimens of Germany, 2022-09-26 onwards
f = pd.read_parquet("raw/VIW_FNT.parquet")
f = f[(f.ORIGIN_SOURCE == "SENTINEL") & (f.ISO_WEEKSTARTDATE >= "2022-09-26")].copy().reset_index(drop=True)
f["monday"] = pd.to_datetime(f.ISO_WEEKSTARTDATE)
for c in ["AH3", "AH1N12009", "ANOTSUBTYPED", "ANOTSUBTYPABLE", "INF_B", "SPEC_PROCESSED_NB"]:
    f[c] = f[c].fillna(0)
rows = []
for _, r in f.iterrows():
    # season = Sep-Aug year pair, split at ISO week 40
    iso = r.monday.isocalendar()
    start = iso.year if iso.week >= 40 else iso.year - 1
    h3, h1, au = int(r.AH3), int(r.AH1N12009), int(r.ANOTSUBTYPED + r.ANOTSUBTYPABLE)
    b = int(r.INF_B)
    n = int(r.SPEC_PROCESSED_NB)
    pos = h3 + h1 + au + b
    rows.append((f"{start}/{str(start + 1)[2:]}", iso.year, iso.week, r.monday.date(), n, au, h3, h1, b, pos,
                 round(100 * pos / n, 1) if n else ""))
for sentinel_file in ["influenza-sentinel-weekly.csv", "../Berlin-multiseason/influenza-sentinel-weekly.csv"]:
  with open(sentinel_file, "w", encoding="utf-8") as out:
    out.write("# Weekly influenza detections of the sentinel specimens of Germany (WHO FluNet, ORIGIN_SOURCE = SENTINEL).\n"
              "# Source: Episerve dataset q4242040001970 (https://data.episerve.zib.de/dataset/q4242040001970), file VIW_FNT.parquet,\n"
              "#   from the WHO FluMart API, CC BY 4.0.\n"
              "# samples = SPEC_PROCESSED_NB; A_unsubtyped = ANOTSUBTYPED + ANOTSUBTYPABLE; A_H3N2 = AH3; A_H1N1pdm09 = AH1N12009;\n"
              "#   B_Victoria = INF_B (all B: B/Yamagata is zero in the whole period, and part of the B detections carry no lineage, so the\n"
              "#   lineage columns would undercount); influenza_positives = their sum; influenza_positivity_pct derived here.\n"
              "# All weeks are present, also those without detections. Season changes at ISO week 40.\n"
              "# Used for the timing and for the subtype split of the import only, never for its level\n"
              "#   (docs/influenza-multiseasonal.md, sections 4.2, 4.3; assumptions A24, A31).\n"
              "season,iso_year,iso_week,monday,samples,A_unsubtyped,A_H3N2,A_H1N1pdm09,B_Victoria,influenza_positives,influenza_positivity_pct\n")
    for r in rows:
        out.write(",".join(str(x) for x in r) + "\n")
