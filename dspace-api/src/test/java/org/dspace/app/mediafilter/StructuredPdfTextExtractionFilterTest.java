package org.dspace.app.mediafilter;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.dspace.content.Item;
import org.junit.Test;

public class StructuredPdfTextExtractionFilterTest {

  private static final StructuredPdfTextExtractionFilter filter = new StructuredPdfTextExtractionFilter();

  @Test
  public void testGetFilteredName() {
    assertEquals("multipage_test.pdf.xml", filter.getFilteredName("multipage_test.pdf"));
  }

  @Test
  public void testGetBundleName() {
    assertEquals("STRUCTURED_TEXT", filter.getBundleName());
  }

  @Test
  public void testGetFormatString() {
    assertEquals("XML", filter.getFormatString());
  }

  @Test
  public void testGetDescription() {
    assertEquals("Extracted structured text", filter.getDescription());
  }

  @Test
  public void testGetDestinationStream() throws Exception {
    Item item = mock(Item.class);
    InputStream source = mock(InputStream.class);

    when(source.read(any(byte[].class))).thenReturn(-1);

    InputStream resultStream = filter.getDestinationStream(item, getMultiPagePDF(), true);

    assertNotNull(resultStream);
    byte[] bytes = new byte[100];
    InputStream expectedInputStream = getExpectedXml();
    assertEquals(expectedInputStream.read(bytes), resultStream.read(bytes));

    resultStream.close();
  }

  private InputStream getMultiPagePDF() {
    return getClass().getResourceAsStream("multipage_test.pdf");
  }

  private InputStream getExpectedXml() {
    return getClass().getResourceAsStream("multipage_expected_result.xml");
  }
}
