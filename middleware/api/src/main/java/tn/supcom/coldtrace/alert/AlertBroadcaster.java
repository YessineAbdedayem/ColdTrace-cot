package tn.supcom.coldtrace.alert;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.websocket.Session;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Keeps the connected WebSocket clients and sends them each alert. */
@ApplicationScoped
public class AlertBroadcaster {

    private static final Logger LOG = Logger.getLogger(AlertBroadcaster.class.getName());

    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final Jsonb jsonb = JsonbBuilder.create();

    public void add(Session session) {
        sessions.add(session);
    }

    public void remove(Session session) {
        sessions.remove(session);
    }

    /** Sends asynchronously, so a slow client cannot delay the MQTT thread. */
    public void broadcast(AlertEvent event) {
        String json = jsonb.toJson(event);
        for (Session session : sessions) {
            try {
                if (session.isOpen()) {
                    session.getAsyncRemote().sendText(json);
                } else {
                    sessions.remove(session);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not send alert to WebSocket client " + session.getId(), e);
                sessions.remove(session);
            }
        }
    }

    @PreDestroy
    void close() {
        try {
            jsonb.close();
        } catch (Exception e) {
            LOG.log(Level.FINE, "JSON-B shutdown error", e);
        }
    }
}
