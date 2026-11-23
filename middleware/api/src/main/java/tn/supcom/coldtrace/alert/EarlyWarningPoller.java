package tn.supcom.coldtrace.alert;

import com.mongodb.client.MongoDatabase;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;
import org.bson.Document;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks the early-warning model (mlops/serving) for a forecast of every known sensor, at a fixed interval,
 * and hands each answer to {@link AlertEngine#applyForecast}.
 *
 * Disabled unless ML_FORECAST_URL is set, e.g. http://model-api:8000 in the Compose stack.
 * The model is optional: when it is down or has no production model, the rule-based alerts work as before,
 * and this only logs the change of state (not every failed call).
 */
@ApplicationScoped
public class EarlyWarningPoller {

    private static final Logger LOG = Logger.getLogger(EarlyWarningPoller.class.getName());
    private static final long DEFAULT_POLL_SECONDS = 30;

    @Inject
    MongoDatabase db;

    @Inject
    AlertEngine engine;
    @Inject
    tn.supcom.coldtrace.ingestion.SensorState states;

    @Resource
    ManagedScheduledExecutorService scheduler;

    private final String baseUrl = trimSlash(System.getenv("ML_FORECAST_URL"));
    // HTTP/1.1: by default Java asks every server to upgrade to HTTP/2 (h2c), which the model API (uvicorn)
    // refuses with an "Unsupported upgrade request" warning on each call.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private ScheduledFuture<?> task;
    /** Last problem logged, so a model that stays down is reported once, and its recovery once. */
    private String lastProblem;
    /** First problem of the current round. Rounds never overlap (fixed delay), so no locking is needed. */
    private String roundProblem;

    void onStartup(@Observes Startup event) {
        if (baseUrl == null) {
            LOG.info("Early warnings disabled: set ML_FORECAST_URL to enable them");
            return;
        }
        long every = pollSeconds();
        task = scheduler.scheduleWithFixedDelay(this::pollAll, every, every, TimeUnit.SECONDS);
        LOG.info("Early warnings from " + baseUrl + " every " + every + " s");
    }

    @PreDestroy
    void stop() {
        if (task != null) {
            task.cancel(false);
        }
    }

    void pollAll() {
        roundProblem = null;
        try {
            for (Document sensor : db.getCollection("sensors").find()) {
                if (!"online".equals(states.status(sensor, states.latest(sensor)))) continue;
                if (!poll(sensor.getString("_id"), sensor.getString("siteId"))) {
                    break; // model API unreachable or not ready: no point asking for the other sensors
                }
            }
            // Also when the model API is down: that is when warnings stop getting forecasts.
            engine.closeStaleEarlyWarnings();
            report(roundProblem);
        } catch (RuntimeException e) {
            // Never let the scheduled task die: it would not be rescheduled.
            LOG.log(Level.WARNING, "Early-warning poll failed", e);
        }
    }

    /** @return false when the model API cannot answer for any sensor right now */
    private boolean poll(String sensorId, String siteId) {
        if (sensorId == null) {
            return true;
        }
        HttpResponse<String> r;
        try {
            r = http.send(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/forecast/" + URLEncoder.encode(sensorId, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            problem("model API unreachable at " + baseUrl + " (" + e.getClass().getSimpleName() + ")");
            return false;
        }
        switch (r.statusCode()) {
            case 200 -> {
                try {
                    engine.applyForecast(siteId, Forecast.parse(r.body()));
                } catch (IllegalArgumentException e) {
                    problem(e.getMessage());
                }
                return true;
            }
            // 404: unknown to the model API; 422: not enough recent readings (sensor just started or silent).
            // Normal situations for one sensor: the silence alert already covers a sensor without readings.
            case 404, 422 -> {
                return true;
            }
            case 503 -> {
                problem("model API has no production model loaded yet");
                return false;
            }
            default -> {
                problem("model API answered HTTP " + r.statusCode() + " for sensor " + sensorId);
                return true;
            }
        }
    }

    private void problem(String message) {
        if (roundProblem == null) {
            roundProblem = message;
        }
    }

    private void report(String message) {
        if (message == null) {
            if (lastProblem != null) {
                LOG.info("Early warnings working again");
            }
        } else if (!message.equals(lastProblem)) {
            LOG.warning("Early warnings paused: " + message);
        }
        lastProblem = message;
    }

    private static long pollSeconds() {
        String v = System.getenv("ML_POLL_SECONDS");
        try {
            long s = v == null || v.isBlank() ? DEFAULT_POLL_SECONDS : Long.parseLong(v.trim());
            return Math.max(5, s);
        } catch (NumberFormatException e) {
            LOG.warning("ML_POLL_SECONDS is not a number: using " + DEFAULT_POLL_SECONDS);
            return DEFAULT_POLL_SECONDS;
        }
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
