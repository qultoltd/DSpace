/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.mediafilter;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.ContentHandlerDecorator;
import org.dspace.app.mediafilter.model.Page;
import org.dspace.app.mediafilter.model.Pages;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.Item;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.xml.sax.SAXException;

public class StructuredPdfTextExtractionFilter extends MediaFilter {
  private final Splitter splitter = new Splitter();
  private final XmlMapper xmlMapper = new XmlMapper();
  private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH_mm_ss_SSS");

  @Override
  public String getFilteredName(String oldFileName) {
    return oldFileName + ".xml";
  }

  @Override
  public String getBundleName() {
    return "STRUCTURED_TEXT";
  }

  @Override
  public String getFormatString() {
    return "XML";
  }

  @Override
  public String getDescription() {
    return "Extracted Structured Text";
  }

  @Override
  public InputStream getDestinationStream(final Item item, final InputStream source, final boolean verbose)
    throws Exception {

    PDDocument document = PDDocument.load(source);
    List<PDDocument> splitPages = splitter.split(document);

    PDFTextStripper stripper = new PDFTextStripper();
    List<Page> pageTexts = new ArrayList<>();

    for (int i = 0; i < splitPages.size(); i++) {
      Page page = new Page(i + 1, stripper.getText(splitPages.get(i)));
      pageTexts.add(page);
    }

    Pages pages = new Pages(pageTexts);

    xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
    File tempFile = File.createTempFile("dspacetextextract" + dateFormat.format(new Date()), ".xml");
    xmlMapper.writeValue(tempFile, pages);

    return Files.newInputStream(Path.of(tempFile.getAbsolutePath()));
  }

  @Override
  public boolean preProcessBitstream(Context c, Item item, Bitstream source, boolean verbose) throws SQLException {
    return "application/pdf".equals(source.getFormat(c).getMIMEType());
  }

}
