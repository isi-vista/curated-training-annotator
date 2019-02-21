package edu.isi.vista.annotationutils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CTEvent {

  private String eventType;

  private int begin;

  private int end;

  private List<Argument> arguments;

  private boolean negative;

  public CTEvent(String eventType, int begin, int end, Collection<Argument> arguments, boolean isNegativeExample) {
    this.eventType = eventType;
    this.begin = begin;
    this.end = end;
    this.arguments = new ArrayList<>(arguments);
    this.negative = isNegativeExample;
  }

  public String getEventType() {
    return eventType;
  }

  public int getBegin() {
    return begin;
  }

  public int getEnd() {
    return end;
  }

  public List<Argument> getArguments() {
    return new ArrayList<>(arguments);
  }

  public boolean isNegative() {
    return negative;
  }
}
