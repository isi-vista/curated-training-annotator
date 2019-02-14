package edu.isi.vista.annotationutils;

public class Argument {

    private String role;

    private int begin;

    private int end;

    public Argument(String role, int begin, int end) {
        this.role = role;
        this.begin = begin;
        this.end = end;
    }

    public String getRole() {
        return role;
    }

    public int getBegin() {
        return begin;
    }

    public int getEnd() {
        return end;
    }
}
