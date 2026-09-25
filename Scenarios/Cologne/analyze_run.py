#!/usr/bin/env python3
"""Plausibility check and calibration evaluation of influenza Cologne runs against the 2025/26 season.

    python3 Scenarios/Cologne/analyze_run.py <run output> [--district Köln] [--no-plot]

<run output> is the "output" directory of a batch (one sub-directory per run) or a single run directory.

* One parameter set (RunInfluenza): prints six checks, writes influenza_check_weekly.tsv and
  influenza_check.png.
* Several parameter sets (CalibrateInfluenza; parameters read from _info.txt): additionally fits the growth
  rate per parameter value, interpolates the value that meets the target (influenza SARI growth, NRW notifications as
  cross-check), writes influenza_calibration.tsv and influenza_calibration.png, and runs the six checks on the grid
  value closest to the estimate.

References (files next to this script): weekly laboratory-confirmed notifications of Nordrhein-Westfalen (IfSG) and
the national influenza SARI hospitalisation incidence (ICOSARI). There is no Cologne-level surveillance, so timing and
shape are compared with NRW and hospital levels with Germany, as an order of magnitude only. Plots need matplotlib.
"""

import argparse
import csv
import datetime as dt
import math
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
NRW_FILE = HERE / "nrw-ifsg-influenza-2025-26.tsv"
SARI_FILE = HERE / "germany-icosari-influenza-sari-2025-26.tsv"

GENERATION_TIME_RANGE = (2.2, 3.6)
AGE_GROUPS = ((0, 14, "0-14"), (15, 59, "15-59"), (60, 200, "60+"))
TAKEOFF_FRACTION = 0.10
RISING_PHASE = (0.05, 0.50)
POPULATION_COLUMNS = ("nSusceptible", "nTotalInfected", "nRecovered", "nDeceasedCumulative")
INFO_FIXED_COLUMNS = ("RunScript", "Config", "RunId", "Output", "seed")


# ---------------------------------------------------------------- helpers

def monday(date):
    return date - dt.timedelta(days=date.weekday())


def iso(week):
    year, number, _ = week.isocalendar()
    return f"{year}-W{number:02d}"


def read_reference(path):
    series = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#") or line.startswith("week"):
            continue
        week, value = line.split("\t")[:2]
        year, number = week.split("-W")
        series[dt.date.fromisocalendar(int(year), int(number), 1)] = float(value)
    return series


def centred_mean(series):
    """Three-week centred mean; the first and last week keep their value."""
    weeks = sorted(series)
    out = {}
    for i, week in enumerate(weeks):
        window = [series[w] for w in weeks[max(i - 1, 0):i + 2]]
        out[week] = sum(window) / len(window)
    return out


def one_file(directory, suffix):
    files = list(directory.glob("*" + suffix))
    if len(files) != 1:
        sys.exit(f"Expected exactly one *{suffix} in {directory}, found {len(files)}")
    return files[0]


def daily_increments(cumulative):
    out, previous = {}, 0.0
    for date in sorted(cumulative):
        out[date] = cumulative[date] - previous
        previous = cumulative[date]
    return out


def weekly_sum(daily):
    out = defaultdict(float)
    for date, value in daily.items():
        out[monday(date)] += value
    return dict(out)


def age_group(age):
    for index, (low, high, _) in enumerate(AGE_GROUPS):
        if low <= age <= high:
            return index
    raise ValueError(f"age {age} outside {AGE_GROUPS}")


def fmt_range(values, pattern="{:.1f}"):
    values = [v for v in values if v is not None]
    if not values:
        return "n/a"
    if len(values) == 1:
        return pattern.format(values[0])
    return f"{pattern.format(statistics.fmean(values))} (seeds {pattern.format(min(values))}-{pattern.format(max(values))})"


def histogram_stats(histogram):
    """Mean, median, quartiles and count of a value -> count histogram."""
    n = sum(histogram.values())
    if not n:
        return None
    mean = sum(k * v for k, v in histogram.items()) / n

    def quantile(q):
        target, seen = q * (n - 1), 0
        for value in sorted(histogram):
            seen += histogram[value]
            if seen > target:
                return value
        return max(histogram)

    return {"mean": mean, "median": quantile(0.5), "q1": quantile(0.25), "q3": quantile(0.75), "n": n}


# ---------------------------------------------------------------- reading runs

def find_runs(root):
    """(run directory, parameters) for every run; parameters come from _info.txt of a batch."""
    if any(root.glob("*.infections.txt")):
        return [(root, {})]
    dirs = sorted(d for d in root.iterdir() if d.is_dir() and any(d.glob("*.infections.txt")))
    if not dirs:
        sys.exit(f"No *.infections.txt in {root} or its sub-directories")

    params_by_dir = {}
    info = root / "_info.txt"
    if info.is_file():
        with info.open(encoding="utf-8") as f:
            for row in csv.DictReader(f, delimiter=";"):
                params = {k: v for k, v in row.items() if k not in INFO_FIXED_COLUMNS}
                params_by_dir[Path(row["Output"]).name] = params
    return [(d, params_by_dir.get(d.name, {})) for d in dirs]


def analyse_run(directory, district):
    cum_infected, cum_sick = {}, {}
    all_cum_infected = defaultdict(float)
    population = None
    with one_file(directory, ".infections.txt").open(encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            date = dt.date.fromisoformat(row["date"])
            all_cum_infected[date] += float(row["nInfectedCumulative"])
            if row["district"] == district:
                cum_infected[date] = float(row["nInfectedCumulative"])
                cum_sick[date] = float(row["nSeriouslySickCumulative"])
                if row["day"] == "1":
                    population = sum(float(row[c]) for c in POPULATION_COLUMNS)
    if population is None:
        sys.exit(f"District '{district}' not found in {directory}")

    imports = defaultdict(float)
    with one_file(directory, ".diseaseImport.tsv").open(encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            if row["strain"] != "SARS_CoV_2":
                imports[dt.date.fromisoformat(row["date"])] += float(row["n"])

    age_of = {}
    population_by_age = [0] * len(AGE_GROUPS)
    with one_file(directory, ".persons.tsv").open(encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            if row["district"] == district:
                group = age_group(int(row["age"]))
                age_of[row["personId"]] = group
                population_by_age[group] += 1

    infection_day = {}
    contacts = []
    infected_by_age = [0] * len(AGE_GROUPS)
    with one_file(directory, ".infectionEpisodes.tsv").open(encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            day = row["day_infectedButNotContagious"]
            if not day:
                continue
            infection_day[(row["personId"], row["episode"])] = int(day)
            if row["source"] == "contact" and row["infectorId"] and row["infectorEpisode"]:
                contacts.append((int(day), row["infectorId"], row["infectorEpisode"]))
            if row["episode"] == "1" and row["personId"] in age_of:
                infected_by_age[age_of[row["personId"]]] += 1
    generation_times = Counter(day - infection_day[(person, episode)] for day, person, episode in contacts
                               if (person, episode) in infection_day)

    infections = weekly_sum(daily_increments(cum_infected))
    return {
        "population": population,
        "infections": infections,
        "admissions": weekly_sum(daily_increments(cum_sick)),
        "imports": weekly_sum(imports),
        "all_infections": weekly_sum(daily_increments(all_cum_infected)),
        "attack_rate": sum(infections.values()) / population,
        "attack_rate_by_age": [i / p if p else float("nan") for i, p in zip(infected_by_age, population_by_age)],
        "generation_times": generation_times,
    }


# ---------------------------------------------------------------- metrics

def takeoff_and_peak(weekly):
    weeks = sorted(weekly)
    if not weeks:
        return None, None, 0.0
    peak_week = max(weeks, key=weekly.get)
    peak = weekly[peak_week]
    if peak <= 0:
        return None, None, 0.0
    takeoff = next(w for w in weeks if weekly[w] >= TAKEOFF_FRACTION * peak)
    return takeoff, peak_week, peak


def growth_rate(weekly):
    """Per-day growth rate from a log-linear fit over the rising phase (5-50 % of the peak, before the peak)."""
    _, peak_week, peak = takeoff_and_peak(weekly)
    if not peak:
        return None
    low, high = RISING_PHASE
    weeks = [w for w in sorted(weekly) if w <= peak_week and low * peak <= weekly[w] <= high * peak]
    if len(weeks) < 3:
        return None
    xs = [(w - weeks[0]).days for w in weeks]
    ys = [math.log(weekly[w]) for w in weeks]
    mx, my = statistics.fmean(xs), statistics.fmean(ys)
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    return slope, weeks[0], weeks[-1]


def mean_series(runs, key):
    weeks = sorted(set().union(*(r[key] for r in runs)))
    return {w: statistics.fmean(r[key].get(w, 0.0) for r in runs) for w in weeks}


def per_100k(weekly, population):
    return {w: v / population * 1e5 for w, v in weekly.items()}


def summarise(runs):
    """Seed-averaged weekly series and the growth rate of one parameter set."""
    population = statistics.fmean(r["population"] for r in runs)
    infections = per_100k(mean_series(runs, "infections"), population)
    admissions = per_100k(mean_series(runs, "admissions"), population)
    fit = growth_rate(infections)
    seed_fits = [growth_rate(per_100k(r["infections"], r["population"])) for r in runs]
    return {
        "infections": infections,
        "admissions": admissions,
        "growth": fit[0] if fit else None,
        "growth_window": (fit[1], fit[2]) if fit else None,
        "growth_seeds": [f[0] if f else None for f in seed_fits],
    }


# ---------------------------------------------------------------- single parameter set

def check_report(runs, nrw, sari, district):
    summary = summarise(runs)
    infections, admissions = summary["infections"], summary["admissions"]
    nrw_smooth = centred_mean(nrw)
    lines = [f"Influenza run check: {len(runs)} seed(s), district {district}", ""]
    if not set(infections) & set(nrw):
        lines += ["WARNING: the run does not cover the 2025/26 reference weeks; timing comparisons are meaningless.", ""]

    def timing(weekly):
        takeoff, peak_week, peak = takeoff_and_peak(weekly)
        return (iso(takeoff), iso(peak_week), peak) if peak else ("none", "none", 0.0)

    m_takeoff, m_peak, m_peak_value = timing(infections)
    n_takeoff, n_peak, _ = timing(nrw_smooth)
    s_takeoff, s_peak, _ = timing(sari)
    lines.append(f"1. Take-off (first week >= {TAKEOFF_FRACTION:.0%} of the peak) and peak week")
    lines.append(f"   model infections {m_takeoff} / {m_peak} (peak {m_peak_value:.0f} per 100k per week)")
    lines.append(f"   NRW IfSG, 3-week mean {n_takeoff} / {n_peak};  ICOSARI influenza SARI {s_takeoff} / {s_peak}")
    lines.append("   (IfSG counts by reporting week, i.e. several days after infection)")

    lines.append("2. Growth rate on the rising phase (log-linear, 5-50 % of the peak), per day")
    for name, weekly in (("model infections", infections), ("NRW IfSG, 3-week mean", nrw_smooth), ("ICOSARI SARI", sari)):
        fit = growth_rate(weekly)
        text = f"{fit[0]:.3f} ({iso(fit[1])}..{iso(fit[2])})" if fit else "n/a (fewer than 3 rising weeks)"
        lines.append(f"   {name}: {text}")

    lines.append(f"3. Attack rate in {district} (share of residents infected during the run)")
    lines.append(f"   all ages {fmt_range([100 * r['attack_rate'] for r in runs], '{:.1f}%')}")
    for index, (_, _, label) in enumerate(AGE_GROUPS):
        lines.append(f"   {label}: {fmt_range([100 * r['attack_rate_by_age'][index] for r in runs], '{:.1f}%')}")
    lines.append("   (a simple SIR with R 1.2-1.35 gives 31-46 %; cohort studies see around a fifth per season)")

    lines.append("4. Imported share of all infections (whole model population)")
    peak_shares, totals = [], []
    for r in runs:
        peak_week = max(r["all_infections"], key=r["all_infections"].get)
        infected = r["all_infections"][peak_week]
        peak_shares.append(100 * r["imports"].get(peak_week, 0.0) / infected if infected else None)
        all_infected = sum(r["all_infections"].values())
        totals.append(100 * sum(r["imports"].values()) / all_infected if all_infected else None)
    lines.append(f"   at the peak week {fmt_range(peak_shares, '{:.2f}%')}; over the run {fmt_range(totals, '{:.2f}%')}")

    lines.append("5. Generation time (infection of the infector to infection of the infected), days")
    stats = histogram_stats(sum((r["generation_times"] for r in runs), Counter()))
    if stats:
        low, high = GENERATION_TIME_RANGE
        verdict = "inside" if low <= round(stats["mean"], 2) <= high else "OUTSIDE"
        lines.append(f"   mean {stats['mean']:.2f}, median {stats['median']}, IQR {stats['q1']}-{stats['q3']}, "
                     f"n={stats['n']}; {verdict} the reported {low}-{high} d")
    else:
        lines.append("   n/a (no contact infections)")

    lines.append(f"6. Hospital admissions per 100k per week: model {district} vs ICOSARI influenza SARI, Germany")
    weeks = sorted(set(admissions) & set(sari))
    if weeks:
        m_peak_adm = max(admissions[w] for w in weeks)
        s_peak_adm = max(sari[w] for w in weeks)
        m_total = sum(admissions[w] for w in weeks)
        s_total = sum(sari[w] for w in weeks)
        ratio = f" (ratio {m_total / s_total:.1f}x)" if s_total else ""
        lines.append(f"   peak {m_peak_adm:.1f} vs {s_peak_adm:.1f}; season total {m_total:.0f} vs {s_total:.0f}{ratio}")
        lines.append("   (SARI counts only the severe-acute-respiratory subset of influenza admissions: expect model >= SARI)")
    else:
        lines.append("   n/a (no overlapping weeks)")
    return "\n".join(lines), summary


def write_weekly(path, runs, summary, nrw, sari):
    infections, admissions = summary["infections"], summary["admissions"]
    nrw_smooth = centred_mean(nrw)
    imports = mean_series(runs, "imports")
    all_infections = mean_series(runs, "all_infections")
    weeks = sorted(set(infections) | set(nrw) | set(sari))
    with path.open("w", encoding="utf-8", newline="") as f:
        out = csv.writer(f, delimiter="\t")
        out.writerow(["week", "model_infections_per100k", "model_admissions_per100k", "model_imported_persons",
                      "model_import_share", "nrw_ifsg_cases", "nrw_ifsg_cases_3wk_mean", "icosari_sari_per100k"])
        for w in weeks:
            share = imports.get(w, 0.0) / all_infections[w] if all_infections.get(w) else ""
            out.writerow([iso(w)] + [("" if v is None else round(v, 4) if isinstance(v, float) else v) for v in (
                infections.get(w), admissions.get(w), imports.get(w), share,
                nrw.get(w), nrw_smooth.get(w), sari.get(w))])


def pyplot():
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        return plt
    except ImportError:
        return None


def plot_check(path, summary, nrw, sari, district, title_suffix=""):
    plt = pyplot()
    if plt is None:
        return False

    def normalised(series):
        peak = max(series.values(), default=0.0)
        weeks = sorted(series)
        return weeks, [series[w] / peak if peak else 0.0 for w in weeks]

    infections, admissions = summary["infections"], summary["admissions"]
    fig, (top, bottom) = plt.subplots(2, 1, figsize=(10, 8), sharex=True)
    for series, label in ((infections, f"model infections, {district}"), (admissions, f"model admissions, {district}"),
                          (centred_mean(nrw), "NRW IfSG notifications, 3-week mean"),
                          (sari, "ICOSARI influenza SARI, Germany")):
        top.plot(*normalised(series), label=label)
    top.set_ylabel("relative to own peak")
    top.set_title("Shape and timing" + title_suffix)
    top.legend()
    for series, label in ((admissions, f"model admissions, {district}"), (sari, "ICOSARI influenza SARI, Germany")):
        weeks = sorted(series)
        bottom.plot(weeks, [series[w] for w in weeks], label=label)
    bottom.set_ylabel("per 100,000 per week")
    bottom.set_title("Hospital admissions (order of magnitude)")
    bottom.legend()
    fig.autofmt_xdate()
    fig.tight_layout()
    fig.savefig(path, dpi=120)
    return True


# ---------------------------------------------------------------- several parameter sets

def interpolate(points, target):
    """Parameter value where the growth rate crosses the target, linear between neighbouring grid values."""
    for (x1, y1), (x2, y2) in zip(points, points[1:]):
        if (y1 - target) * (y2 - target) <= 0 and y1 != y2:
            return x1 + (target - y1) * (x2 - x1) / (y2 - y1)
    return None


def calibration(groups, nrw, sari):
    """groups: parameter value -> list of analysed runs. Returns the report text, rows and the estimate."""
    sari_fit, nrw_fit = growth_rate(sari), growth_rate(centred_mean(nrw))
    targets = {"SARI": sari_fit[0] if sari_fit else None, "NRW": nrw_fit[0] if nrw_fit else None}

    rows = []
    for value in sorted(groups):
        runs = groups[value]
        summary = summarise(runs)
        _, peak_week, _ = takeoff_and_peak(summary["infections"])
        takeoff, _, _ = takeoff_and_peak(summary["infections"])
        stats = histogram_stats(sum((r["generation_times"] for r in runs), Counter()))
        rows.append({
            "value": value,
            "growth": summary["growth"],
            "growth_seeds": summary["growth_seeds"],
            "takeoff": iso(takeoff) if takeoff else "none",
            "peak_week": iso(peak_week) if peak_week else "none",
            "attack_rate": statistics.fmean(r["attack_rate"] for r in runs),
            "admissions_peak": max(summary["admissions"].values(), default=0.0),
            "admissions_total": sum(summary["admissions"].values()),
            "generation_time": stats["mean"] if stats else None,
        })

    points = [(row["value"], row["growth"]) for row in rows if row["growth"] is not None]
    estimates = {name: (interpolate(points, t) if t is not None else None) for name, t in targets.items()}

    lines = ["Calibration grid (seed means; growth = log-linear fit over 5-50 % of the peak, per day)", ""]
    lines.append(f"{'value':>7} {'growth':>7} {'seeds':>15} {'take-off':>9} {'peak':>9} {'attack':>7} "
                 f"{'adm peak':>9} {'adm total':>10} {'GT':>5}")
    for row in rows:
        seeds = "/".join("n/a" if g is None else f"{g:.3f}" for g in row["growth_seeds"])
        growth = "n/a" if row["growth"] is None else f"{row['growth']:.3f}"
        gt = "n/a" if row["generation_time"] is None else f"{row['generation_time']:.2f}"
        lines.append(f"{row['value']:>7.3f} {growth:>7} {seeds:>15} {row['takeoff']:>9} {row['peak_week']:>9} "
                     f"{100 * row['attack_rate']:>6.1f}% {row['admissions_peak']:>9.1f} {row['admissions_total']:>10.0f} "
                     f"{gt:>5}")
    lines.append("")
    lines.append("   adm = hospital admissions per 100k (per week at the peak / over the run); GT = mean generation time, d")
    lines.append("")
    for name, label in (("SARI", "target, ICOSARI influenza SARI"), ("NRW", "cross-check, NRW IfSG 3-week mean")):
        target, estimate = targets[name], estimates[name]
        if target is None:
            lines.append(f"{label}: no growth rate in the reference")
        elif not points:
            lines.append(f"{label}: growth {target:.3f}/day, but no grid value has a measurable growth rate "
                         f"(rising phase shorter than three weeks everywhere)")
        elif estimate is None:
            growths = [g for _, g in points]
            lines.append(f"{label}: growth {target:.3f}/day is outside the grid's range "
                         f"{min(growths):.3f}-{max(growths):.3f}: extend the grid")
        else:
            lines.append(f"{label}: growth {target:.3f}/day -> value {estimate:.3f}")
    return "\n".join(lines), rows, targets, estimates


def write_calibration(path, rows):
    with path.open("w", encoding="utf-8", newline="") as f:
        out = csv.writer(f, delimiter="\t")
        out.writerow(["value", "growth_per_day", "growth_per_seed", "takeoff_week", "peak_week", "attack_rate",
                      "admissions_peak_per100k", "admissions_total_per100k", "generation_time_mean"])
        for row in rows:
            out.writerow([row["value"], "" if row["growth"] is None else round(row["growth"], 5),
                          "/".join("" if g is None else f"{g:.5f}" for g in row["growth_seeds"]),
                          row["takeoff"], row["peak_week"], round(row["attack_rate"], 5),
                          round(row["admissions_peak"], 3), round(row["admissions_total"], 3),
                          "" if row["generation_time"] is None else round(row["generation_time"], 3)])


def plot_calibration(path, rows, targets, estimates, parameter):
    plt = pyplot()
    if plt is None:
        return False
    fig, (left, right) = plt.subplots(1, 2, figsize=(12, 4.5))
    with_growth = [row for row in rows if row["growth"] is not None]
    left.plot([r["value"] for r in with_growth], [r["growth"] for r in with_growth], "o-", label="model (seed mean)")
    for row in rows:
        seeds = [g for g in row["growth_seeds"] if g is not None]
        left.plot([row["value"]] * len(seeds), seeds, ".", color="grey")
    for name, style in (("SARI", "-"), ("NRW", "--")):
        if targets[name] is not None:
            left.axhline(targets[name], color="red", linestyle=style, label=f"{name} {targets[name]:.3f}/day")
        if estimates[name] is not None:
            left.axvline(estimates[name], color="red", linestyle=style, alpha=0.4)
    left.set_xlabel(parameter)
    left.set_ylabel("growth rate of the rising phase, per day")
    left.legend()
    right.plot([r["value"] for r in rows], [100 * r["attack_rate"] for r in rows], "o-")
    right.set_xlabel(parameter)
    right.set_ylabel("attack rate in the city, %")
    right.axhspan(15, 25, color="green", alpha=0.1, label="around a fifth per season")
    right.legend()
    fig.tight_layout()
    fig.savefig(path, dpi=120)
    return True


# ---------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("output", type=Path, help="batch output directory or a single run directory")
    parser.add_argument("--district", default="Köln")
    parser.add_argument("--no-plot", action="store_true")
    args = parser.parse_args()

    nrw, sari = read_reference(NRW_FILE), read_reference(SARI_FILE)
    groups = defaultdict(list)
    parameters = set()
    for directory, params in find_runs(args.output):
        parameters.update(params)
        groups[tuple(sorted(params.items()))].append(analyse_run(directory, args.district))

    if len(groups) == 1:
        runs = next(iter(groups.values()))
        suffix = ""
    else:
        if len(parameters) != 1:
            sys.exit(f"Calibration needs exactly one varied parameter besides the seed, found {sorted(parameters)}")
        parameter = parameters.pop()
        by_value = {float(dict(key)[parameter]): runs for key, runs in groups.items()}
        text, rows, targets, estimates = calibration(by_value, nrw, sari)
        print(text)
        table = args.output / "influenza_calibration.tsv"
        write_calibration(table, rows)
        print(f"\ncalibration table: {table}")
        if not args.no_plot:
            png = args.output / "influenza_calibration.png"
            print(f"plot: {png}" if plot_calibration(png, rows, targets, estimates, parameter)
                  else "plot: skipped, matplotlib not installed")

        reference = estimates["SARI"] if estimates["SARI"] is not None else min(by_value)
        nearest = min(by_value, key=lambda v: abs(v - reference))
        runs = by_value[nearest]
        suffix = f" ({parameter} = {nearest})"
        print(f"\n\nChecks for the grid value closest to the estimate, {parameter} = {nearest}:\n")

    text, summary = check_report(runs, nrw, sari, args.district)
    print(text)
    weekly = args.output / "influenza_check_weekly.tsv"
    write_weekly(weekly, runs, summary, nrw, sari)
    print(f"\nweekly table: {weekly}")
    if not args.no_plot:
        png = args.output / "influenza_check.png"
        print(f"plot: {png}" if plot_check(png, summary, nrw, sari, args.district, suffix)
              else "plot: skipped, matplotlib not installed")


if __name__ == "__main__":
    main()
