package tn.supcom.coldtrace.report;

import org.bson.Document;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The compliance report as a PDF (A4 landscape): summary, one row per sensor, then the incident list. */
public final class PdfReport {

    private static final float MARGIN = 36;
    private static final float ROW = 14;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.of("UTC"));

    private final SimplePdf pdf = new SimplePdf(SimplePdf.A4_LANDSCAPE_W, SimplePdf.A4_LANDSCAPE_H);
    private float y;

    private PdfReport() { }

    public static byte[] render(ReportService.Summary s, List<Document> incidents, String generatedBy) {
        PdfReport r = new PdfReport();
        r.write(s, incidents, generatedBy);
        return r.pdf.toBytes();
    }

    private void write(ReportService.Summary s, List<Document> incidents, String generatedBy) {
        y = pdf.height() - MARGIN - 6;
        pdf.text(MARGIN, y, 18, true, "ColdTrace - Rapport de conformité");
        y -= 20;
        pdf.text(MARGIN, y, 10, false, "Période : " + when(s.from()) + " -> " + when(s.to()) + " (UTC)   ·   Site : "
                + (s.siteId() == null ? "tous" : s.siteId()));
        y -= ROW;
        pdf.text(MARGIN, y, 10, false, "Généré le " + when(s.generatedAt()) + " UTC par " + generatedBy);
        y -= 24;

        // summary boxes
        String[][] boxes = {
                {"Conformité", pct(s.compliancePercent())},
                {"Mesures", Long.toString(s.readings())},
                {"Incidents", Integer.toString(s.incidents())},
                {"Hors plage", minutes(s.alarmMinutes())},
                {"Délai d'acquittement", s.meanAckMinutes() == null ? "--" : minutes(s.meanAckMinutes())},
                {"Non acquittés", Integer.toString(s.unacknowledged())}};
        float boxW = (pdf.width() - 2 * MARGIN - 5 * 8) / 6;
        for (int i = 0; i < boxes.length; i++) {
            float x = MARGIN + i * (boxW + 8);
            pdf.gray(0.93f);
            pdf.rect(x, y - 30, boxW, 42);
            pdf.gray(0.35f);
            pdf.text(x + 8, y, 8, false, SimplePdf.fit(boxes[i][0], 8, boxW - 16));
            pdf.gray(0);
            pdf.text(x + 8, y - 22, 16, true, boxes[i][1]);
        }
        y -= 56;
        if (!s.incidentsByMetric().isEmpty()) {
            StringBuilder metrics = new StringBuilder("Incidents par type : ");
            for (Map.Entry<String, Integer> e : s.incidentsByMetric().entrySet()) {
                metrics.append(metric(e.getKey())).append(" ").append(e.getValue()).append("   ");
            }
            pdf.text(MARGIN, y, 9, false, metrics.toString());
            y -= 20;
        }

        // sensors
        section("Capteurs");
        float[] cols = {50, 50, 115, 60, 90, 55, 110, 65, 55, 60, 60};
        String[] head = {"Site", "Capteur", "Nom", "Profil", "Limites °C", "Mesures", "Min / moy / max °C",
                "Conformité", "Incidents", "Hors plage", "MTTA"};
        header(cols, head);
        for (ReportService.SensorKpi k : s.perSensor()) {
            ensure(ROW, cols, head);
            cells(cols, false, k.siteId(), k.sensorId(), k.name(), k.profile(), limits(k.limits()),
                    Long.toString(k.readings()),
                    k.readings() == 0 ? "--" : num(k.temperatureMin()) + " / " + num(k.temperatureAvg()) + " / " + num(k.temperatureMax()),
                    pct(k.compliancePercent()), Integer.toString(k.incidents()), minutes(k.alarmMinutes()),
                    k.meanAckMinutes() == null ? "--" : minutes(k.meanAckMinutes()));
        }
        y -= 16;

        // incidents
        section("Incidents (" + incidents.size() + ")");
        float[] icols = {90, 90, 50, 50, 80, 60, 70, 90, 190};
        String[] ihead = {"Ouvert", "Fermé", "Site", "Capteur", "Type", "Pic", "Statut", "Acquitté par", "Commentaire"};
        if (incidents.isEmpty()) {
            pdf.text(MARGIN, y, 9, false, "Aucun incident sur la période.");
            y -= ROW;
        } else {
            header(icols, ihead);
            for (Document i : incidents) {
                ensure(ROW, icols, ihead);
                String ack = i.getString("acknowledgedBy");
                cells(icols, false, when(i.getDate("openedAt")), when(i.getDate("closedAt")), i.getString("siteId"),
                        i.getString("sensorId"), metric(i.getString("metric")), num(i.get("peakValue")),
                        "closed".equals(i.getString("status")) ? "fermé" : "ouvert",
                        ack == null ? "non acquitté" : ack, i.getString("comment"));
            }
        }
        footer();
    }

    private void section(String title) {
        ensure(40, null, null);
        pdf.text(MARGIN, y, 12, true, title);
        y -= 18;
    }

    private void header(float[] cols, String[] head) {
        pdf.gray(0.88f);
        pdf.rect(MARGIN, y - 4, sum(cols), ROW);
        pdf.gray(0);
        cells(cols, true, (Object[]) head);
    }

    private void cells(float[] cols, boolean bold, Object... values) {
        float x = MARGIN;
        for (int c = 0; c < cols.length; c++) {
            String v = values[c] == null ? "" : values[c].toString();
            pdf.text(x + 2, y, 8, bold, SimplePdf.fit(v, 8, cols[c] - 4));
            x += cols[c];
        }
        y -= ROW;
    }

    /** A new page (with the table header again) when the next {@code needed} points do not fit. */
    private void ensure(float needed, float[] cols, String[] head) {
        if (y - needed > MARGIN + 16) return;
        footer();
        pdf.newPage();
        y = pdf.height() - MARGIN;
        if (cols != null) header(cols, head);
    }

    private void footer() {
        pdf.gray(0.4f);
        pdf.text(MARGIN, MARGIN - 12, 8, false, "ColdTrace · rapport généré automatiquement · page " + pdf.pageCount());
        pdf.gray(0);
    }

    private static float sum(float[] cols) {
        float s = 0;
        for (float c : cols) s += c;
        return s;
    }

    static String metric(String m) {
        if (m == null) return "incident";
        return switch (m) {
            case "temperature" -> "température";
            case "humidity" -> "humidité";
            case "door" -> "porte ouverte";
            case "silence" -> "silence capteur";
            case "early_warning" -> "alerte précoce";
            default -> m;
        };
    }

    private static String limits(ReportService.Limits l) {
        if (l == null) return "--";
        if (l.temperatureMin() != null && l.temperatureMax() != null) return num(l.temperatureMin()) + " .. " + num(l.temperatureMax());
        if (l.temperatureMax() != null) return "<= " + num(l.temperatureMax());
        if (l.temperatureMin() != null) return ">= " + num(l.temperatureMin());
        return "--";
    }

    private static String num(Object v) {
        return v instanceof Number n ? String.format(Locale.ROOT, "%.1f", n.doubleValue()) : "--";
    }

    private static String pct(Double v) {
        return v == null ? "--" : String.format(Locale.ROOT, "%.1f %%", v);
    }

    private static String minutes(double m) {
        if (m < 60) return String.format(Locale.ROOT, "%.1f min", m);
        long total = Math.round(m);
        return String.format(Locale.ROOT, "%d h %02d", total / 60, total % 60);
    }

    private static String when(String iso) {
        return iso == null || iso.isEmpty() ? "--" : WHEN.format(java.time.Instant.parse(iso));
    }

    private static String when(Date d) {
        return d == null ? "--" : WHEN.format(d.toInstant());
    }
}
