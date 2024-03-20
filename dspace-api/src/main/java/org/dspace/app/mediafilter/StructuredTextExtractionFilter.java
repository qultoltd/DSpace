package org.dspace.app.mediafilter;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import org.dspace.app.mediafilter.model.Page;
import org.dspace.app.mediafilter.model.Pages;
import org.dspace.content.Item;

public class StructuredTextExtractionFilter extends MediaFilter {

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
    return "Extracted structured text";
  }

  @Override
  public InputStream getDestinationStream(final Item item, final InputStream source, final boolean verbose)
    throws Exception {
    String extractedText;

    PDDocument document = PDDocument.load(source);
    Splitter splitter = new Splitter();
    List<PDDocument> splitPages = splitter.split(document);

    PDFTextStripper stripper = new PDFTextStripper();
    List<Page> pageTexts = new ArrayList<>();

    for (int i = 0; i < splitPages.size(); i++) {
      Page page = new Page(i + 1, stripper.getText(splitPages.get(i)));
      pageTexts.add(page);
    }

    Pages pages = new Pages(pageTexts);

    XmlMapper xmlMapper = new XmlMapper();
    xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    xmlMapper.writeValue(byteArrayOutputStream, pages);
    extractedText = byteArrayOutputStream.toString();

    if (StringUtils.isNotEmpty(extractedText)) {
      return new ByteArrayInputStream(extractedText.getBytes(StandardCharsets.UTF_8));
    }

    return null;
  }
}
