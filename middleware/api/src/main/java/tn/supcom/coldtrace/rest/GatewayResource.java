package tn.supcom.coldtrace.rest;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** GET /api/gateways: the latest status of each site's gateway (collection "gateways", _id = siteId). */
@Path("/gateways")
@RequestScoped
public class GatewayResource {

    @Inject
    MongoDatabase db;

    public record GatewayView(String siteId, String gatewayId, String status, String ts, String updatedAt,
                              Long buffered, Long forwarded, Long replayed, Long rejected, Long dropped) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<GatewayView> list() {
        List<GatewayView> result = new ArrayList<>();
        for (Document d : db.getCollection("gateways").find().sort(Sorts.ascending("_id"))) {
            result.add(new GatewayView(d.getString("_id"), d.getString("gatewayId"), d.getString("status"),
                    iso(d.getDate("ts")), iso(d.getDate("updatedAt")),
                    toLong(d.get("buffered")), toLong(d.get("forwarded")), toLong(d.get("replayed")),
                    toLong(d.get("rejected")), toLong(d.get("dropped"))));
        }
        return result;
    }

    private static String iso(Date d) {
        return d == null ? null : d.toInstant().toString();
    }

    private static Long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
