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
import java.util.ArrayList;
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
import org.dspace.content.Bitstream;
import org.dspace.content.Item;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.xml.sax.SAXException;

public class StructuredPdfTextExtractionFilter extends MediaFilter {

  private final Splitter splitter = new Splitter();
  private final XmlMapper xmlMapper = new XmlMapper();
  private final static Logger log = LogManager.getLogger();

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

    ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
    boolean useTemporaryFile = configurationService.getBooleanProperty("textextractor.use-temp-file", false);

    if (useTemporaryFile) {
      return extractUsingTempFile(source, verbose);
    }

    String extractedText;

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
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    xmlMapper.writeValue(byteArrayOutputStream, pages);
    extractedText = byteArrayOutputStream.toString();

    if (StringUtils.isNotEmpty(extractedText)) {
      return new ByteArrayInputStream(extractedText.getBytes(StandardCharsets.UTF_8));
    }

    return null;
  }

  private InputStream extractUsingTempFile(InputStream source, boolean verbose)
    throws IOException, TikaException, SAXException {
    File tempExtractedTextFile = File.createTempFile("dspacetextextract" + source.hashCode(), ".txt");

    if (verbose) {
      System.out.println("(Verbose mode) Extracted text was written to temporary file at " +
        tempExtractedTextFile.getAbsolutePath());
    } else {
      tempExtractedTextFile.deleteOnExit();
    }

    try (FileWriter writer = new FileWriter(tempExtractedTextFile, StandardCharsets.UTF_8)) {
      ContentHandlerDecorator handler = new BodyContentHandler(new ContentHandlerDecorator() {

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
          try {
            writer.append(new String(ch), start, length);
          } catch (IOException e) {
            String errorMsg = String.format("Could not append to temporary file at %s " +
                "when performing text extraction",
              tempExtractedTextFile.getAbsolutePath());
            log.error(errorMsg, e);
            throw new SAXException(errorMsg, e);
          }
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
          try {
            writer.append(new String(ch), start, length);
          } catch (IOException e) {
            String errorMsg = String.format("Could not append to temporary file at %s " +
                "when performing text extraction",
              tempExtractedTextFile.getAbsolutePath());
            log.error(errorMsg, e);
            throw new SAXException(errorMsg, e);
          }
        }
      });

      AutoDetectParser parser = new AutoDetectParser();
      Metadata metadata = new Metadata();
      parser.parse(source, handler, metadata);
    }

    return new FileInputStream(tempExtractedTextFile);
  }
}
