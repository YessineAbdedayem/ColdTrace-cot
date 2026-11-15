"""Step 8: the whole training pipeline in one command.

  [generate] -> prepare -> train (log, register, promote) -> check

Each stage runs as its own process and the pipeline stops at the first failure (non-zero exit),
so it can run unattended: by hand, on a schedule, or in CI.

  python run.py                              prepare + train + check, on the data already generated
  python run.py --generate --days 30         regenerate the synthetic history first
  python run.py --generate --days 3 --test-days 1 --val-days 0.5    small run (CI)
"""

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
# In the Docker image generate.py sits next to this file; in the repository it is in ../generator.
GENERATOR = HERE / "generate.py" if (HERE / "generate.py").exists() else HERE.parent / "generator" / "generate.py"


def stage(name, args):
    print(f"\n=== {name} ===", flush=True)
    started = time.time()
    result = subprocess.run([sys.executable, *args], cwd=HERE)
    if result.returncode != 0:
        print(f"\nPipeline stopped: stage '{name}' failed (exit {result.returncode})", flush=True)
        sys.exit(result.returncode)
    print(f"--- {name}: done in {time.time() - started:.0f} s", flush=True)
    return time.time() - started


def check_production():
    """The model labelled production must load by name and give finite predictions."""
    import mlflow
    import numpy as np
    import pandas as pd

    from features import FEATURES
    from tracking import ALIAS, MODEL_NAME

    model = mlflow.pyfunc.load_model(f"models:/{MODEL_NAME}@{ALIAS}")
    sample = pd.read_parquet(Path(os.environ.get("DATA_DIR", "/data")) / "test.parquet").head(100)
    pred = model.predict(sample[FEATURES])
    if len(pred) != len(sample) or not np.isfinite(pred).all():
        raise SystemExit("the production model returned invalid predictions")
    print(f"{MODEL_NAME}@{ALIAS} (run {model.metadata.run_id[:8]}) loads and predicts: OK")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--generate", action="store_true", help="regenerate the synthetic history first (replaces it)")
    parser.add_argument("--days", default="30", help="history length when generating")
    parser.add_argument("--seed", default="42", help="generator seed")
    parser.add_argument("--end", help="end of the generated history (ISO date), for reproducible data")
    parser.add_argument("--test-days", default="8")
    parser.add_argument("--val-days", default="4")
    parser.add_argument("--force-promote", action="store_true")
    args = parser.parse_args()

    started = time.time()
    if args.generate:
        gen = [str(GENERATOR), "--days", args.days, "--seed", args.seed, "--reset"]
        if args.end:
            gen += ["--end", args.end]
        stage("generate", gen)
    stage("prepare", ["prepare.py", "--test-days", args.test_days])
    stage("train", ["train.py", "--val-days", args.val_days] + (["--force-promote"] if args.force_promote else []))
    if os.environ.get("MLFLOW_TRACKING_URI"):
        print("\n=== check ===", flush=True)
        check_production()
    print(f"\nPipeline finished in {time.time() - started:.0f} s")


if __name__ == "__main__":
    main()
