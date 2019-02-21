package edu.isi.vista.annotationutils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CTEvent {

  private String type = "CTEvent";

  private int begin;

  private int end;

  private List<Argument> arguments;

  private boolean negative;

  public CTEvent(int begin, int end, Collection<Argument> arguments, boolean isNegativeExample) {
    this.begin = begin;
    this.end = end;
    this.arguments = new ArrayList<>(arguments);
    this.negative = isNegativeExample;
  }

  public String getType() {
    return type;
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
