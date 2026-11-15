package tn.supcom.coldtrace.alert;

import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.util.logging.Level;
import java.util.logging.Logger;

/** WebSocket endpoint that receives alert events. It has no login yet: that comes with Jakarta Security. */
@ServerEndpoint("/ws/alerts")
public class AlertSocket {

    private static final Logger LOG = Logger.getLogger(AlertSocket.class.getName());

    @Inject
    AlertBroadcaster broadcaster;

    @OnOpen
    public void onOpen(Session session) {
        broadcaster.add(session);
    }

    @OnClose
    public void onClose(Session session) {
        broadcaster.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        LOG.log(Level.FINE, "WebSocket error", error);
        broadcaster.remove(session);
    }
}
