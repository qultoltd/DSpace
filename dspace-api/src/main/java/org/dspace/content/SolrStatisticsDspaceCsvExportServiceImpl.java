package org.dspace.content;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.FieldStatsInfo;
import org.apache.solr.client.solrj.response.RangeFacet;
import org.apache.solr.common.params.FacetParams;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.service.SolrStatisticsDspaceCsvExportService;
import org.dspace.core.Context;
import org.dspace.scripts.handler.DSpaceRunnableHandler;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.util.SolrImportExportException;

/**
 * @author dsipos
 */
public class SolrStatisticsDspaceCsvExportServiceImpl implements SolrStatisticsDspaceCsvExportService {

  private static final ThreadLocal<DateFormat> SOLR_DATE_FORMAT;
  private static final ThreadLocal<DateFormat> SOLR_DATE_FORMAT_NO_MS;
  private static final ThreadLocal<DateFormat> EXPORT_DATE_FORMAT;
  public static final int ROWS_PER_FILE = 10_000;
  private static final String EXPORT_CSV = "exportCSV";

  private static final String MULTIPLE_VALUES_SPLITTER = ",";
  private static final String EXPORT_SEP = "_export_";
  private static final ConfigurationService configurationService
    = DSpaceServicesFactory.getInstance().getConfigurationService();

  static {
    SOLR_DATE_FORMAT = ThreadLocal.withInitial(() -> {
      SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
      simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
      return simpleDateFormat;
    });
    SOLR_DATE_FORMAT_NO_MS = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"));
    EXPORT_DATE_FORMAT = ThreadLocal.withInitial(() -> {
      SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM");
      simpleDateFormat.setTimeZone(TimeZone.getDefault());
      return simpleDateFormat;
    });
  }

  @Override
  public void handleExport(final Context context, final String indexName,
    final String fromWhen, final DSpaceRunnableHandler handler)
    throws SolrServerException, IOException, SolrImportExportException, SQLException, AuthorizeException {
    String solrUrl = makeSolrUrl(indexName);
    String timeField = makeTimeField(indexName);

    handler.logInfo(String.format("Export Index [%s] using [%s] Time Field[%s] FromWhen[%s]", indexName,
      solrUrl, timeField, fromWhen));
    if (StringUtils.isBlank(solrUrl)) {
      throw new SolrImportExportException(
        "Could not construct solr URL for index" + indexName + ", aborting export.");
    }
    HttpSolrClient solr = new HttpSolrClient.Builder(solrUrl).build();
    SolrQuery query = new SolrQuery("*:*");
    if (StringUtils.isNotBlank(fromWhen)) {
      String lastValueFilter = makeFilterQuery(timeField, fromWhen);
      if (StringUtils.isNotBlank(lastValueFilter)) {
        query.addFilterQuery(lastValueFilter);
      }
    }

    query.setRows(0);
    query.setGetFieldStatistics(timeField);
    Map<String, FieldStatsInfo> fieldInfo = solr.query(query).getFieldStatsInfo();
    if (fieldInfo == null || !fieldInfo.containsKey(timeField)) {
      throw new SolrImportExportException(String.format(
        "Queried [%s].  No fieldInfo found while exporting index [%s] time field [%s] from [%s]. Export " +
          "cancelled.",
        solrUrl, indexName, timeField, fromWhen));
    }
    FieldStatsInfo timeFieldInfo = fieldInfo.get(timeField);
    if (timeFieldInfo == null || timeFieldInfo.getMin() == null) {
      throw new SolrImportExportException(String.format(
        "Queried [%s].  No earliest date found while exporting index [%s] time field [%s] from [%s]. Export " +
          "cancelled.",
        solrUrl, indexName, timeField, fromWhen));
    }
    Date earliestTimestamp = (Date) timeFieldInfo.getMin();

    query.setGetFieldStatistics(false);
    query.clearSorts();
    query.setRows(0);
    query.setFacet(true);
    query.add(FacetParams.FACET_RANGE, timeField);
    query.add(FacetParams.FACET_RANGE_START, SOLR_DATE_FORMAT.get().format(earliestTimestamp) + "/MONTH");
    query.add(FacetParams.FACET_RANGE_END, "NOW/MONTH+1MONTH");
    query.add(FacetParams.FACET_RANGE_GAP, "+1MONTH");
    query.setFacetMinCount(1);

    List<RangeFacet.Count> monthFacets = solr.query(query).getFacetRanges().get(0).getCounts();
    for (RangeFacet.Count monthFacet : monthFacets) {
      Date monthStartDate;
      String monthStart = monthFacet.getValue();
      try {
        monthStartDate = SOLR_DATE_FORMAT_NO_MS.get().parse(monthStart);
      } catch (java.text.ParseException e) {
        throw new SolrImportExportException("Could not read start of month batch as date: " + monthStart, e);
      }
      int docsThisMonth = monthFacet.getCount();

      SolrQuery monthQuery = new SolrQuery("*:*");
      monthQuery.setRows(ROWS_PER_FILE);
      monthQuery.set("wt", "csv");
      monthQuery.set("fl", "*");
      monthQuery.setParam("csv.mv.separator", MULTIPLE_VALUES_SPLITTER);

      monthQuery.addFilterQuery(timeField + ":[" + monthStart + " TO " + monthStart + "+1MONTH]");

      for (int i = 0; i < docsThisMonth; i += ROWS_PER_FILE) {
        monthQuery.setStart(i);
        URL url = new URL(solrUrl + "/select?" + monthQuery.toString());
        String filename = makeExportFilename(indexName, monthStartDate, docsThisMonth, i);

//        FileUtils.copyURLToFile(url, file);
        InputStream exportCSV = new URL(url.toString()).openStream();
        handler.writeFilestream(context, filename, exportCSV, EXPORT_CSV);
        handler.logInfo(String.format(
          "Solr export to file is complete.  Export for Index [%s] Month [%s] Batch [%d] Num Docs [%d]",
          indexName, monthStart, i, docsThisMonth));

      }
    }
  }

  /**
   * Returns the full URL for the specified index name.
   * @param indexName the index name whose Solr URL is required. If the index name starts with
   *                  &quot;statistics&quot; or is &quot;authority&quot;, the Solr base URL will be looked up
   *                  in the corresponding DSpace configuration file. Otherwise, it will fall back to a default.
   * @return the full URL to the Solr index, as a String.
   */
  private static String makeSolrUrl(String indexName) {
    if (indexName.startsWith("statistics")) {
      // TODO account for year shards properly?
      return configurationService.getProperty("solr-statistics.server") + indexName
        .replaceFirst("statistics", "");
    } else if ("authority".equals(indexName)) {
      return configurationService.getProperty("solr.authority.server");
    }
    return "http://localhost:8080/solr/" + indexName; // TODO better default?
  }

  /**
   * Returns a time field for the specified index name that is suitable for incremental export.
   * @param indexName the index name whose Solr URL is required.
   * @return the name of the time field, or null if no suitable field can be determined.
   */
  private static String makeTimeField(String indexName) {
    if (indexName.startsWith("statistics")) {
      return "time";
    } else if ("authority".equals(indexName)) {
      return "last_modified_date";
    }
    return null; // TODO some sort of default?
  }

  /**
   * Return a filter query that represents the export date range passed in as lastValue
   * @param timeField the time field to use for the date range
   * @param lastValue the requested date range, see options for acceptable values
   * @return a filter query representing the date range, or null if no suitable date range can be created.
   */
  private static String makeFilterQuery(String timeField, String lastValue) {
    if ("m".equals(lastValue)) {
      // export data from the previous month
      return timeField + ":[NOW/MONTH-1MONTH TO NOW/MONTH]";
    }

    int days;
    if ("d".equals(lastValue)) {
      days = 1;
    } else {
      // other acceptable value: a number, specifying how many days back to export
      days = Integer.valueOf(lastValue); // TODO check value?
    }
    return timeField + ":[NOW/DAY-" + days + "DAYS TO " + SOLR_DATE_FORMAT.get().format(new Date()) + "]";
  }

  /**
   * Creates a filename for the export batch.
   * @param indexName    The name of the index being exported.
   * @param exportStart  The start timestamp of the export
   * @param totalRecords The total number of records in the export.
   * @param index        The index of the current batch.
   * @return A file name that is appropriate to use for exporting the batch of data described by the parameters.
   */
  private static String makeExportFilename(String indexName, Date exportStart, long totalRecords, int index) {
    String exportFileNumber = "";
    if (totalRecords > ROWS_PER_FILE) {
      exportFileNumber = StringUtils
        .leftPad("" + (index / ROWS_PER_FILE), (int) Math.ceil(Math.log10(totalRecords / ROWS_PER_FILE)), "0");
    }
    return indexName
      + EXPORT_SEP
      + EXPORT_DATE_FORMAT.get().format(exportStart)
      + (StringUtils.isNotBlank(exportFileNumber) ? "_" + exportFileNumber : "")
      + ".csv";
  }
}
