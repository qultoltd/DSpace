/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 * <p>
 * http://www.dspace.org/license/
 */
package org.dspace.app.solrstatistics;

import java.io.InputStream;
import java.sql.SQLException;
import org.apache.commons.cli.ParseException;
import org.dspace.content.service.SolrStatisticsDspaceCsvExportService;
import org.dspace.core.Context;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.utils.DSpace;

/**
 * Metadata exporter to allow the batch export of metadata into a file
 * @author Stuart Lewis
 */
public class SolrStatisticsExport extends DSpaceRunnable<SolrStatisticsExportScriptConfiguration> {

  private boolean help = false;
  private String lastValue = null;

  private SolrStatisticsDspaceCsvExportService solrStatisticsDspaceCsvExportService = new DSpace().getServiceManager()
    .getServicesByType(SolrStatisticsDspaceCsvExportService.class).get(0);

  private EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();

  @Override
  public void internalRun() throws Exception {

    if (help) {
      logHelpInfo();
      printHelp();
      return;
    }
    Context context = new Context();
    context.turnOffAuthorisationSystem();
    try {
      context.setCurrentUser(ePersonService.find(context, this.getEpersonIdentifier()));
    } catch (SQLException e) {
      handler.handleException(e);
    }
   solrStatisticsDspaceCsvExportService
      .handleExport(context, "statistics", lastValue, handler);
    context.restoreAuthSystemState();
    context.complete();
  }

  protected void logHelpInfo() {
    handler.logInfo("\nfull export: solr-export-statistics");
    handler.logInfo("export:  solr-export-statistics  [-a export]  [-i statistics]");
  }

  @Override
  public SolrStatisticsExportScriptConfiguration getScriptConfiguration() {
    return new DSpace().getServiceManager().getServiceByName("solr-export-statistics",
      SolrStatisticsExportScriptConfiguration.class);
  }

  @Override
  public void setup() throws ParseException {

    if (commandLine.hasOption('h')) {
      help = true;
      return;
    }
    if (commandLine.hasOption('l')) {
      lastValue = commandLine.getOptionValue('l');
    }
    if (commandLine.hasOption('a')) {
      lastValue = null;
    }
  }
}
