package tn.supcom.coldtrace.model;

import org.bson.Document;

public class Site {

    private String id;
    private String name;
    private String location;

    public Site() {
    }

    public Site(String id, String name, String location) {
        this.id = id;
        this.name = name;
        this.location = location;
    }

    public Document toDocument() {
        return new Document("_id", id)
                .append("name", name)
                .append("location", location);
    }

    public static Site fromDocument(Document doc) {
        return new Site(doc.getString("_id"), doc.getString("name"), doc.getString("location"));
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
