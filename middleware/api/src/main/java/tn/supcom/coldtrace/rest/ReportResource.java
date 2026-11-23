package tn.supcom.coldtrace.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.StreamingOutput;
import tn.supcom.coldtrace.business.ApiProblem;
import tn.supcom.coldtrace.business.Catalog;
import tn.supcom.coldtrace.report.Csv;
import tn.supcom.coldtrace.report.PdfReport;
import tn.supcom.coldtrace.report.ReportService;
import tn.supcom.coldtrace.report.ReportWindow;
import tn.supcom.coldtrace.security.Access;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * History, indicators and the compliance report (cahier des charges EF-06 and EF-07).
 * All take from / to (ISO-8601 instants; default: the last 24 hours, at most 366 days).
 *
 * GET /api/reports/history?sensorId=S01&points=300   readings averaged per time bucket, limits, incidents
 * GET /api/reports/kpi?siteId=site01                 indicators per sensor and in total
 * GET /api/reports/compliance.csv?siteId=...         the report as CSV (indicators + incident list)
 * GET /api/reports/compliance.pdf?siteId=...         the same report as PDF
 * GET /api/reports/measurements.csv?sensorId=...     raw readings (audit), at most 200 000 rows
 */
@Path("/reports")
@RequestScoped
@RolesAllowed({Access.ADMIN, Access.QUALITY, Access.OPERATOR})
public class ReportResource {

    private static final int MAX_INCIDENTS = 5000;

    @Inject ReportService reports;
    @Context SecurityContext security;

    @GET
    @Path("history")
    @Produces(MediaType.APPLICATION_JSON)
    public ReportService.History history(@QueryParam("sensorId") String sensorId, @QueryParam("from") String from,
                                         @QueryParam("to") String to, @QueryParam("points") @DefaultValue("300") int points) {
        if (sensorId == null) throw ApiProblem.invalid("sensorId is required");
        return reports.history(Catalog.id(sensorId), window(from, to), points);
    }

    @GET
    @Path("kpi")
    @Produces(MediaType.APPLICATION_JSON)
    public ReportService.Summary kpi(@QueryParam("siteId") String siteId, @QueryParam("from") String from,
                                     @QueryParam("to") String to) {
        return reports.summary(window(from, to), site(siteId));
    }

    @GET
    @Path("compliance.csv")
    @Produces("text/csv")
    public Response complianceCsv(@QueryParam("siteId") String siteId, @QueryParam("from") String from,
                                  @QueryParam("to") String to) {
        ReportWindow w = window(from, to);
        String site = site(siteId);
        var summary = reports.summary(w, site);
        var incidents = reports.incidents(w, site, MAX_INCIDENTS);
        StreamingOutput body = out -> {
            var writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            Csv.compliance(writer, summary, incidents, caller());
            writer.flush();
        };
        return download(body, "text/csv; charset=utf-8", name("conformite", site, w, "csv"));
    }

    @GET
    @Path("compliance.pdf")
    @Produces("application/pdf")
    public Response compliancePdf(@QueryParam("siteId") String siteId, @QueryParam("from") String from,
                                  @QueryParam("to") String to) {
        ReportWindow w = window(from, to);
        String site = site(siteId);
        byte[] pdf = PdfReport.render(reports.summary(w, site), reports.incidents(w, site, MAX_INCIDENTS), caller());
        return download(pdf, "application/pdf", name("conformite", site, w, "pdf"));
    }

    @GET
    @Path("measurements.csv")
    @Produces("text/csv")
    public Response measurementsCsv(@QueryParam("siteId") String siteId, @QueryParam("sensorId") String sensorId,
                                    @QueryParam("from") String from, @QueryParam("to") String to) {
        ReportWindow w = window(from, to);
        String site = site(siteId);
        if (sensorId != null) Catalog.id(sensorId);
        StreamingOutput body = out -> {
            var writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            Csv.readingsHeader(writer);
            reports.readings(w, site, sensorId, m -> {
                try {
                    Csv.reading(writer, m);
                } catch (java.io.IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            writer.flush();
        };
        return download(body, "text/csv; charset=utf-8", name("mesures", sensorId != null ? sensorId : site, w, "csv"));
    }

    private static ReportWindow window(String from, String to) {
        return ReportWindow.parse(from, to, Instant.now());
    }

    private static String site(String siteId) {
        return siteId == null || siteId.isBlank() ? null : Catalog.id(siteId);
    }

    private String caller() {
        return security.getUserPrincipal() == null ? "?" : security.getUserPrincipal().getName();
    }

    private static String name(String kind, String scope, ReportWindow w, String ext) {
        LocalDate a = w.from().atZone(ZoneOffset.UTC).toLocalDate(), b = w.to().atZone(ZoneOffset.UTC).toLocalDate();
        return "coldtrace-" + kind + (scope == null ? "" : "-" + scope) + "-" + a + "_" + b + "." + ext;
    }

    private static Response download(Object body, String type, String filename) {
        return Response.ok(body, type).header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Cache-Control", "no-store").build();
    }
}
