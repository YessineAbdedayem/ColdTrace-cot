package tn.supcom.coldtrace.report;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import tn.supcom.coldtrace.business.ApiProblem;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportTest {

    static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

    static Date at(String iso) { return Date.from(Instant.parse(iso)); }

    @Test
    void windowDefaultsToTheLast24Hours() {
        ReportWindow w = ReportWindow.parse(null, null, NOW);
        assertEquals(NOW, w.to());
        assertEquals(Instant.parse("2026-11-22T12:00:00Z"), w.from());
    }

    @Test
    void windowRejectsBadInput() {
        assertThrows(ApiProblem.class, () -> ReportWindow.parse("yesterday", null, NOW));
        assertThrows(ApiProblem.class, () -> ReportWindow.parse("2026-11-23T12:00:00Z", "2026-11-23T11:00:00Z", NOW));
        assertThrows(ApiProblem.class, () -> ReportWindow.parse("2024-01-01T00:00:00Z", "2026-01-01T00:00:00Z", NOW));
    }

    @Test
    void bucketsNeverGoBelowOneReading() {
        ReportWindow hour = ReportWindow.parse("2026-11-23T11:00:00Z", "2026-11-23T12:00:00Z", NOW);
        assertEquals(12000, hour.bucketMillis(300));
        assertEquals(5000, hour.bucketMillis(2000));
        ReportWindow week = ReportWindow.parse("2026-11-16T12:00:00Z", "2026-11-23T12:00:00Z", NOW);
        assertEquals(2016000, week.bucketMillis(300));
    }

    @Test
    void alarmTimeIsCountedInsideTheWindowOnly() {
        ReportWindow w = ReportWindow.parse("2026-11-23T10:00:00Z", "2026-11-23T12:00:00Z", NOW);
        IncidentStats s = new IncidentStats();
        // started before the window, closed inside: 30 min count
        s.add(new Document("metric", "temperature").append("status", "closed")
                .append("openedAt", at("2026-11-23T09:00:00Z")).append("closedAt", at("2026-11-23T10:30:00Z"))
                .append("acknowledgedAt", at("2026-11-23T09:10:00Z")), w);
        // still open: until the end of the window, 15 min
        s.add(new Document("metric", "humidity").append("status", "open").append("openedAt", at("2026-11-23T11:45:00Z")), w);
        // door: counted as an incident, not as out-of-range time
        s.add(new Document("metric", "door").append("status", "closed").append("openedAt", at("2026-11-23T11:00:00Z"))
                .append("closedAt", at("2026-11-23T11:20:00Z")).append("acknowledgedAt", at("2026-11-23T11:30:00Z")), w);
        assertEquals(3, s.count());
        assertEquals(1, s.open());
        assertEquals(1, s.unacknowledged());
        assertEquals(45.0, s.alarmMinutes());
        assertEquals(20.0, s.meanAckMinutes());   // (10 + 30) / 2
        assertEquals(Map.of("door", 1, "humidity", 1, "temperature", 1), s.byMetric());

        IncidentStats total = new IncidentStats();
        total.addAll(s);
        total.addAll(s);
        assertEquals(6, total.count());
        assertEquals(90.0, total.alarmMinutes());
    }

    @Test
    void complianceIsAPercentageOfReadings() {
        assertNull(IncidentStats.compliance(0, 0));
        assertEquals(100.0, IncidentStats.compliance(10, 10));
        assertEquals(66.7, IncidentStats.compliance(2, 3));
    }

    static ReportService.Summary summary() {
        var k = new ReportService.SensorKpi("site01", "S01", "cold room, S01", "cold",
                new ReportService.Limits(0.0, 4.0, 50.0, 70.0), 720, -1.5, 3.2, 7.9, 61.0, 97.5, 1,
                Map.of("temperature", 1), 18.0, 4.0, 0, 0);
        return new ReportService.Summary("2026-11-22T12:00:00Z", "2026-11-23T12:00:00Z", null, NOW.toString(), 1, 720,
                97.5, 1, Map.of("temperature", 1), 18.0, 4.0, 0, 0, List.of(k));
    }

    static List<Document> incidents() {
        return List.of(new Document("siteId", "site01").append("sensorId", "S01").append("metric", "temperature")
                .append("status", "closed").append("openedAt", at("2026-11-23T08:00:00Z"))
                .append("closedAt", at("2026-11-23T08:18:00Z")).append("peakValue", 7.9)
                .append("acknowledgedBy", "qualite").append("acknowledgedAt", at("2026-11-23T08:04:00Z"))
                .append("comment", "=cmd|' /C calc'!A0 door left open, \"checked\""));
    }

    @Test
    void csvQuotesCellsAndNeutralisesFormulas() throws Exception {
        StringWriter out = new StringWriter();
        Csv.compliance(out, summary(), incidents(), "qualite");
        String csv = out.toString();
        assertEquals('﻿', csv.charAt(0));
        assertTrue(csv.contains("site01,S01,\"cold room, S01\",cold,0.0,4.0,50.0,70.0,720,-1.5,3.2,7.9,61.0,97.5,1,18.0,4.0,0,0"), csv);
        assertTrue(csv.contains("\"'=cmd|' /C calc'!A0 door left open, \"\"checked\"\"\""), csv);
        assertTrue(csv.contains("2026-11-23T08:00:00Z"));
    }

    @Test
    void pdfIsAWellFormedDocument() {
        byte[] pdf = PdfReport.render(summary(), incidents(), "qualite");
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-1.4"));
        assertTrue(text.endsWith("%%EOF\n"));
        assertTrue(text.contains("/Type /Catalog"));
        assertTrue(text.contains("Rapport de conformité"), "accents in WinAnsi");
        // every xref offset points at its object
        int xref = Integer.parseInt(text.substring(text.lastIndexOf("startxref\n") + 10, text.lastIndexOf("\n%%EOF")).trim());
        String[] lines = text.substring(xref).split("\n");
        int count = Integer.parseInt(lines[1].split(" ")[1]);
        for (int n = 1; n < count; n++) {
            int offset = Integer.parseInt(lines[2 + n].substring(0, 10));
            assertTrue(text.startsWith(n + " 0 obj", offset), "object " + n);
        }
    }

    @Test
    void pdfBreaksLongListsIntoPages() {
        Document one = incidents().get(0);
        List<Document> many = java.util.Collections.nCopies(120, one);
        String text = new String(PdfReport.render(summary(), many, "qualite"), StandardCharsets.ISO_8859_1);
        int pages = Integer.parseInt(text.replaceAll("(?s).*/Type /Pages /Count (\\d+).*", "$1"));
        assertTrue(pages >= 3, "pages: " + pages);
    }

    @Test
    void pdfTextIsEscaped() {
        assertEquals("a \\(b\\) \\\\ c", SimplePdf.escape("a (b) \\ c"));
        assertEquals("ab...", SimplePdf.fit("abcdefghijklmnop", 10, 31)); // 31 pt at 10 pt: 5 characters
        assertEquals("short", SimplePdf.fit("short", 10, 31));
    }
}
