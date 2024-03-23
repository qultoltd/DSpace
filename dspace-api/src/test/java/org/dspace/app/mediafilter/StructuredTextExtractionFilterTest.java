package org.dspace.app.mediafilter;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.dspace.content.Item;
import org.junit.Test;

public class StructuredTextExtractionFilterTest {

  private static final String EXPECTED_RESULT_STRING = "<pages>\n"
    + "  <page>\n"
    + "    <pageNumber>1</pageNumber>\n"
    + "    <text>A Text Extraction Test Document&#xd;\n"
    + "for&#xd;\n"
    + "DSpace&#xd;\n"
    + "This is a text. For the next sixty seconds this software will conduct a test of the DSpace text&#xd;\n"
    + "extraction facility.&#xd;\n"
    + "This is only a text. This is a paragraph that followed the first that lived in the document that&#xd;\n"
    + "Jack built.&#xd;\n"
    + "Lorem ipsum dolor sit amet. The quick brown fox jumped over the lazy dog. Yow! Are we&#xd;\n"
    + "having fun yet?&#xd;\n"
    + "This has been a test of the DSpace text extraction system. In the event of actual content you&#xd;\n"
    + "would care what is written here&#xd;\n"
    + "</text>\n"
    + "  </page>\n"
    + "  <page>\n"
    + "    <pageNumber>2</pageNumber>\n"
    + "    <text>This is still a text.&#xd;\n"
    + "This is only a text, but on a separate page. This is a paragraph that followed the first that&#xd;\n"
    + "lived in the document that Jack built.&#xd;\n"
    + "Lorem ipsum dolor sit amet. The quick brown fox jumped over the lazy dog.&#xd;\n"
    + "This has been a test of the DSpace structured text extraction system. In the event of actual&#xd;\n"
    + "content you would care what is written here&#xd;\n"
    + "</text>\n"
    + "  </page>\n"
    + "</pages>\n";

  @Test
  public void testGetFilteredName() {
    StructuredTextExtractionFilter filter = new StructuredTextExtractionFilter();
    assertEquals("test.pdf.xml", filter.getFilteredName("test.pdf"));
  }

  @Test
  public void testGetBundleName() {
    StructuredTextExtractionFilter filter = new StructuredTextExtractionFilter();
    assertEquals("STRUCTURED_TEXT", filter.getBundleName());
  }

  @Test
  public void testGetFormatString() {
    StructuredTextExtractionFilter filter = new StructuredTextExtractionFilter();
    assertEquals("XML", filter.getFormatString());
  }

  @Test
  public void testGetDescription() {
    StructuredTextExtractionFilter filter = new StructuredTextExtractionFilter();
    assertEquals("Extracted structured text", filter.getDescription());
  }

  @Test
  public void testGetDestinationStream() throws Exception {
    Item item = mock(Item.class);
    InputStream source = mock(InputStream.class);

    when(source.read(any(byte[].class))).thenReturn(-1);

    StructuredTextExtractionFilter filter = new StructuredTextExtractionFilter();
    InputStream resultStream = filter.getDestinationStream(item, getMultiPagePDF(), true);

    assertNotNull(resultStream);
    byte[] bytes = new byte[100];
    InputStream expectedInputStream = new ByteArrayInputStream(EXPECTED_RESULT_STRING.getBytes());
    assertEquals(expectedInputStream.read(bytes), resultStream.read(bytes));

    resultStream.close();
  }

  private InputStream getMultiPagePDF() {
    return getClass().getResourceAsStream("multipage_test.pdf");
  }
}
