package org.dspace.app.mediafilter.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

@JacksonXmlRootElement(localName = "pages")
public class Pages {

  @JacksonXmlProperty(localName = "page")
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<Page> pageList;

  public Pages(final List<Page> pageList) {
    this.pageList = pageList;
  }

  public List<Page> getPageList() {
    return pageList;
  }

  public void setPageList(final List<Page> page) {
    this.pageList = page;
  }
}
