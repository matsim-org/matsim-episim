#!/usr/bin/env python
# -*- coding: utf-8 -*-

import argparse
from os import path

import numpy as np
import optuna
import pandas as pd
from calibrate import objective_unconstrained

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run calibrations with optuna.")
    parser.add_argument("n_trials", metavar='N', type=int, nargs="?", help="Number of trials", default=60)
    parser.add_argument("--batch", type=str, help="Path to info.txt of batch run", default="_info.txt")
    parser.add_argument("--scenario", type=str, help="Scenario module used for calibration", default="SyntheticScenario")
    parser.add_argument("--no-resume", action="store_true", default=False, help="Disable resuming from existing result")
    parser.add_argument("--runs", type=int, default=8, help="Number of runs per objective")
    parser.add_argument("--prefix", type=str, help="Prefix used for the properties", default="syn.")
    parser.add_argument("--jvm-opts", type=str, default="-Xmx8G")
    args = parser.parse_args()

    info = pd.read_csv(args.batch, sep=";")
    gb = [x for x in info.columns if x not in {"Config", "Output", "RunId", "RunScript", "seed"}]

    batch = info.groupby(by=gb).agg(run=("RunId", "min"))
    batch["param"] = np.nan
    batch["error"] = np.nan
    batch["target"] = np.nan

    names = batch.index.names

    csv = "%sresult.csv" % args.prefix

    resume = None
    if not args.no_resume and path.exists(csv):
        resume = pd.read_csv(csv, index_col=names)

    for idx, run in batch.iterrows():
        try:
            run = resume.loc[idx]
            if not np.isnan(run.param):
                print("Skipping run", run)
                continue
        except:
            pass

        # for i in batch
        study = optuna.create_study(
            study_name=run.run, storage="sqlite:///%sbatch.db" % args.prefix, load_if_exists=True,
            direction="minimize"
        )

        for i, value in enumerate(idx):
            args.jvm_opts += " -D%s%s=%s" % (args.prefix, names[i], value)

        # Copy all args to study
        for k, v in args.__dict__.items():
            study.set_user_attr(k, v)

        print("Starting with options:", args.jvm_opts)
        try:
            study.optimize(objective_unconstrained, n_trials=args.n_trials)
            batch.loc[idx, "param"] = study.best_params["calibrationParameter"]
            batch.loc[idx, "error"] = study.best_value
            batch.loc[idx, "target"] = study.best_trial.user_attrs["target"]
        except:
            print("Failed calibrating batch run")

        batch.to_csv(csv)
