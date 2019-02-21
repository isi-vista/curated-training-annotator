package edu.isi.vista.annotationutils;

import java.util.ArrayList;
import java.util.List;

public class Document {

  private String id;

  private List<CTEvent> events;

  public Document(String id, List<CTEvent> events) {
    this.id = id;
    this.events = new ArrayList<>(events);
  }

  public String getId() {
    return id;
  }

  public List<CTEvent> getEvents() {
    return new ArrayList<>(events);
  }

}
