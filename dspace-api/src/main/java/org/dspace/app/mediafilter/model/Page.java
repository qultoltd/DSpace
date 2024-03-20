package org.dspace.app.mediafilter.model;

public class Page {

  private int pageNumber;
  private String text;

  public Page(int pageNumber, String text) {
    this.pageNumber = pageNumber;
    this.text = text;
  }

  public int getPageNumber() {
    return pageNumber;
  }

  public void setPageNumber(final int pageNumber) {
    this.pageNumber = pageNumber;
  }

  public String getText() {
    return text;
  }

  public void setText(final String text) {
    this.text = text;
  }
}
