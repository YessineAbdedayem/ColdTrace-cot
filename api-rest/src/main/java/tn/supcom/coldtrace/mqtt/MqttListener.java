package tn.supcom.coldtrace.mqtt;

import com.mongodb.client.MongoDatabase;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import tn.supcom.coldtrace.model.Measurement;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Subscribes to sensor readings on Mosquitto and stores each valid one in coldtrace.measurements.
 *
 * Env: MQTT_URL (default tcp://localhost:1883), MQTT_USER, MQTT_PASSWORD, MQTT_CA_PATH (PEM, optional).
 */
@ApplicationScoped
public class MqttListener implements MqttCallbackExtended {

    private static final Logger LOG = Logger.getLogger(MqttListener.class.getName());
    private static final String CLIENT_ID = "coldtrace-api";
    private static final String TOPIC = "coldtrace/+/+/measurements";
    private static final int QOS = 1;
    private static final long RETRY_SECONDS = 5;

    @Inject
    MongoDatabase db;

    @Resource
    ManagedScheduledExecutorService scheduler;

    private final Jsonb jsonb = JsonbBuilder.create();
    private MqttAsyncClient client;
    private MqttConnectOptions options;
    private volatile boolean stopping;

    void onStartup(@Observes @Priority(10) Startup event) {
        try {
            String url = env("MQTT_URL", "tcp://localhost:1883");
            client = new MqttAsyncClient(url, CLIENT_ID, new MemoryPersistence());
            client.setCallback(this);
            options = buildOptions();
            connect();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "MQTT listener not started: invalid configuration", e);
        }
    }

    private MqttConnectOptions buildOptions() throws Exception {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setAutomaticReconnect(true);
        o.setCleanSession(false);
        String user = System.getenv("MQTT_USER");
        if (!isBlank(user)) {
            o.setUserName(user);
            String password = System.getenv("MQTT_PASSWORD");
            o.setPassword(password == null ? new char[0] : password.toCharArray());
        }
        String caPath = System.getenv("MQTT_CA_PATH");
        if (!isBlank(caPath)) {
            o.setSocketFactory(socketFactoryTrusting(Path.of(caPath)));
        }
        return o;
    }

    /** Paho's automatic reconnect only starts after a first successful connect, so we retry that one ourselves. */
    private void connect() {
        if (stopping) {
            return;
        }
        try {
            client.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    // subscribing happens in connectComplete
                }

                @Override
                public void onFailure(IMqttToken token, Throwable e) {
                    LOG.warning("MQTT connect to " + client.getServerURI() + " failed (" + e
                            + "), retrying in " + RETRY_SECONDS + "s");
                    scheduleConnect();
                }
            });
        } catch (MqttException e) {
            LOG.log(Level.WARNING, "MQTT connect error, retrying in " + RETRY_SECONDS + "s", e);
            scheduleConnect();
        }
    }

    private void scheduleConnect() {
        if (!stopping) {
            scheduler.schedule(this::connect, RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Called after the first connect and after every automatic reconnect: (re)subscribe each time. */
    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        LOG.info((reconnect ? "Reconnected" : "Connected") + " to MQTT broker " + serverURI);
        try {
            client.subscribe(TOPIC, QOS, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    LOG.info("Subscribed to " + TOPIC + " (QoS " + QOS + ")");
                }

                @Override
                public void onFailure(IMqttToken token, Throwable e) {
                    LOG.log(Level.SEVERE, "Subscribe to " + TOPIC + " failed", e);
                }
            });
        } catch (MqttException e) {
            LOG.log(Level.SEVERE, "Subscribe to " + TOPIC + " failed", e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        LOG.warning("MQTT connection lost (" + cause + "), reconnecting automatically");
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // Never let an exception escape: Paho would drop the connection.
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            Measurement m;
            try {
                m = jsonb.fromJson(payload, Measurement.class);
            } catch (RuntimeException e) {
                LOG.warning("Rejected message on " + topic + ": invalid JSON: " + payload);
                return;
            }
            String error = validate(m);
            if (error != null) {
                LOG.warning("Rejected message on " + topic + ": " + error + ": " + payload);
                return;
            }
            db.getCollection("measurements").insertOne(m.toDocument());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to store message from " + topic, e);
        }
    }

    /** Returns why the reading must be rejected, or null if it is valid. */
    static String validate(Measurement m) {
        if (m == null) return "empty payload";
        if (isBlank(m.getSensorId())) return "missing sensorId";
        if (isBlank(m.getSiteId())) return "missing siteId";
        if (isBlank(m.getType())) return "missing type";
        if (!Measurement.TYPES.contains(m.getType())) return "unknown type '" + m.getType() + "'";
        if (m.getValue() == null) return "missing value";
        if (isBlank(m.getUnit())) return "missing unit";
        if (isBlank(m.getTs())) return "missing ts";
        try {
            Instant.parse(m.getTs());
        } catch (DateTimeParseException e) {
            return "ts is not ISO-8601";
        }
        return null;
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // subscriber only
    }

    @PreDestroy
    void stop() {
        stopping = true;
        if (client != null) {
            try {
                if (client.isConnected()) {
                    client.disconnect().waitForCompletion(2000);
                }
                client.close(true);
            } catch (MqttException e) {
                LOG.log(Level.FINE, "MQTT shutdown error", e);
            }
        }
        try {
            jsonb.close();
        } catch (Exception ignored) {
            // nothing to do
        }
    }

    private static SSLSocketFactory socketFactoryTrusting(Path caPem) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        try (InputStream in = Files.newInputStream(caPem)) {
            int i = 0;
            for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trustStore.setCertificateEntry("ca-" + i++, cert);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx.getSocketFactory();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return isBlank(value) ? defaultValue : value;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
