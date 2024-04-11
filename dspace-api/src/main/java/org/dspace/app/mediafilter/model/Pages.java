/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
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

}
