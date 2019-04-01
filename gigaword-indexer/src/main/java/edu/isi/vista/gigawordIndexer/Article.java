package edu.isi.vista.gigawordIndexer;

public class Article {

  private String id;

  private String text;

  private int segments = 0; // LTF only

  private boolean failed = false;

  public static Article failedArticle(String id, String text) {
    Article a = new Article(id, text);
    a.failed = true;
    return a;
  }

  public static Article failedArticle(String id, String text, int segments) {
    Article a = new Article(id, text, segments);
    a.failed = true;
    return a;
  }

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

  public boolean failed() {
    return failed;
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
