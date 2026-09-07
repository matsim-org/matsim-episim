FROM apache/airflow:3.3.1

ARG AIRFLOW_VERSION=3.3.1

COPY requirements.txt /tmp/requirements.txt

RUN pip install --no-cache-dir \
    "apache-airflow==${AIRFLOW_VERSION}" \
    -r /tmp/requirements.txt
