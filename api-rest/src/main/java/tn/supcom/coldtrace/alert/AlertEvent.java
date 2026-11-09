package tn.supcom.coldtrace.alert;

import tn.supcom.coldtrace.model.Incident;

/** The JSON message pushed to WebSocket clients. */
public class AlertEvent {

    public static final String OPENED = "incident.opened";
    public static final String CLOSED = "incident.closed";

    private final String event;
    private final Incident incident;

    public AlertEvent(String event, Incident incident) {
        this.event = event;
        this.incident = incident;
    }

    public String getEvent() { return event; }
    public Incident getIncident() { return incident; }
}
