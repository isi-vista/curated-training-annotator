package edu.isi.vista.gigawordIndexer;

public class Article {

  private String id;
  private String text;
  private int segments = 0; // LTF only

  public Article(String id, String text) {
    this.id = id;
    this.text = text;
  }

  public Article(String id, String text, int segments) {
    this.id = id;
    this.text = text;
    this.segments = segments;
  }

  public String getId() {
    return id;
  }

  public String getText() {
    return text;
  }

  public int getSegments() {
    return segments;
  }

  @Override
  public String toString() {
    return "Article [id="
        + id
        + ", text="
        + text.substring(0, Math.min(100, text.length() - 1))
        + "...]";
  }

}
