package edu.isi.vista.projectcreator;

public class ResponseStatus {

  private int code;

  private String message;

  public ResponseStatus(int code, String message) {
    this.code = code;
    this.message = message;
  }

  public int getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }
}
