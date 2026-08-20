# Local observability

Start the Spring Boot application on the host, then start the visualization stack:

```powershell
mvn spring-boot:run
docker compose --profile observability up -d
```

Grafana is available at `http://localhost:3000` and is provisioned with the **ONDA Agentic RAG Overview** dashboard. Sign in with `GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD` from `.env`; both default to `admin` for local use.

Prometheus scrapes `http://host.docker.internal:8080/actuator/prometheus` every 15 seconds. Its own UI is available at `http://localhost:9090`.

Tempo receives OpenTelemetry traces at `http://localhost:4318/v1/traces`. In Grafana, open **Explore**, choose the **Tempo** data source, and search for the `onda-agentic-rag` service to inspect individual requests and their spans.
