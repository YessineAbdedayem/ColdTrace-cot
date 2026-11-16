package tn.supcom.coldtrace.alert;

import jakarta.inject.Inject;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.security.Principal;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebSocket endpoint that receives alert events: ws://host/ws/alerts?access_token=TOKEN (the IAM's token; browsers
 * cannot send an Authorization header on a WebSocket). The token is checked on the upgrade request by
 * BearerTokenMechanism; a session that still arrives without a logged-in user is closed here (defence in depth).
 */
@ServerEndpoint("/ws/alerts")
public class AlertSocket {

    private static final Logger LOG = Logger.getLogger(AlertSocket.class.getName());

    @Inject
    AlertBroadcaster broadcaster;

    @OnOpen
    public void onOpen(Session session) {
        Principal user = session.getUserPrincipal();
        if (user == null || "anonymous".equals(user.getName())) {
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "login required"));
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not close an unauthenticated WebSocket", e);
            }
            return;
        }
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
