package tn.supcom.coldtrace.model;

import org.bson.Document;

import java.time.Instant;
import java.util.Date;

/**
 * Message from coldtrace/{siteId}/gateway/status. The birth and last-will messages carry only
 * {@code status}; the heartbeat also carries {@code ts} and the counters.
 */
public class GatewayStatus {

    private String gatewayId;
    private String status;
    private String ts;
    private Long buffered;
    private Long forwarded;
    private Long replayed;
    private Long rejected;
    private Long dropped;

    public GatewayStatus() {
    }

    /** Fields to $set on the site's gateway document. Absent fields keep their previous value. */
    public Document toDocument() {
        Document doc = new Document("status", status).append("updatedAt", new Date());
        appendIfPresent(doc, "gatewayId", gatewayId);
        if (ts != null) {
            doc.append("ts", Date.from(Instant.parse(ts)));
        }
        appendIfPresent(doc, "buffered", buffered);
        appendIfPresent(doc, "forwarded", forwarded);
        appendIfPresent(doc, "replayed", replayed);
        appendIfPresent(doc, "rejected", rejected);
        appendIfPresent(doc, "dropped", dropped);
        return doc;
    }

    private static void appendIfPresent(Document doc, String key, Object value) {
        if (value != null) {
            doc.append(key, value);
        }
    }

    public String getGatewayId() { return gatewayId; }
    public void setGatewayId(String gatewayId) { this.gatewayId = gatewayId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTs() { return ts; }
    public void setTs(String ts) { this.ts = ts; }
    public Long getBuffered() { return buffered; }
    public void setBuffered(Long buffered) { this.buffered = buffered; }
    public Long getForwarded() { return forwarded; }
    public void setForwarded(Long forwarded) { this.forwarded = forwarded; }
    public Long getReplayed() { return replayed; }
    public void setReplayed(Long replayed) { this.replayed = replayed; }
    public Long getRejected() { return rejected; }
    public void setRejected(Long rejected) { this.rejected = rejected; }
    public Long getDropped() { return dropped; }
    public void setDropped(Long dropped) { this.dropped = dropped; }
}
