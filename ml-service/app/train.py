"""
Standalone training entry point.

    python -m app.train

Trains the sentiment (Logistic Regression) and intent (Linear SVM) models from the
seed CSVs in ../data and saves them to MODEL_DIR. Replace the CSVs with a larger
labeled dataset and re-run to retrain — the API also exposes POST /ml/train.
"""
import json

from . import models


def main():
    result = models.train_all()
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
