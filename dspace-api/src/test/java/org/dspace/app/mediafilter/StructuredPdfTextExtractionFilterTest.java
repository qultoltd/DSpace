package org.dspace.app.mediafilter;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

import java.sql.SQLException;
import org.dspace.app.mediafilter.model.Page;
import org.dspace.app.mediafilter.model.Pages;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Item;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.Test;

public class StructuredPdfTextExtractionFilterTest {

  private static final StructuredPdfTextExtractionFilter filter = new StructuredPdfTextExtractionFilter();
  private static final XmlMapper xmlMapper = new XmlMapper();

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
    assertEquals("Extracted Structured Text", filter.getDescription());
  }

  @Test
  public void testGetDestinationStream() throws Exception {
    Item item = mock(Item.class);

    InputStream resultStream = filter.getDestinationStream(item, getMultiPagePDF(), true);

    assertNotNull(resultStream);

    InputStream expectedInputStream = getExpectedXml();
    Pages expectedPages = xmlMapper.readValue(expectedInputStream, Pages.class);
    Pages resultPages = xmlMapper.readValue(resultStream, Pages.class);

    assertEquals(expectedPages, resultPages);

    resultStream.close();
  }

  @Test
  public void testPreProcessBitstream() throws SQLException {
    Context context = mock(Context.class);
    Item item = mock(Item.class);

    Bitstream source = mock(Bitstream.class);
    BitstreamFormat bsFormat = mock(BitstreamFormat.class);
    when(source.getFormat(context)).thenReturn(bsFormat);
    when(bsFormat.getMIMEType()).thenReturn("application/pdf");

    assertTrue(filter.preProcessBitstream(context, item, source, true));

    when(bsFormat.getMIMEType()).thenReturn("image/png");
    assertFalse(filter.preProcessBitstream(context, item, source, true));
  }

  private InputStream getMultiPagePDF() {
    return getClass().getResourceAsStream("multipage_test.pdf");
  }

  private InputStream getExpectedXml() {
    return getClass().getResourceAsStream("multipage_expected_result.xml");
  }

}
