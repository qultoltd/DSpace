package org.dspace.content.service;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import org.apache.solr.client.solrj.SolrServerException;
import org.dspace.authorize.AuthorizeException;
import org.dspace.core.Context;
import org.dspace.scripts.handler.DSpaceRunnableHandler;
import org.dspace.util.SolrImportExportException;

/**
 * @author dsipos
 */
public interface SolrStatisticsDspaceCsvExportService {
  /**
   * Exports documents from the given index to the specified target directory in batches of #ROWS_PER_FILE,
   * starting at fromWhen (or all documents).
   * See #makeExportFilename for the file names that are generated.
   * @param indexName The index to export.
   * @param fromWhen  Optionally, from when to export. See options for allowed values. If null or empty, all
   *                  documents will be exported.
   * @throws SolrServerException       if there is a problem with exporting the index.
   * @throws IOException               if there is a problem creating the files or communicating with Solr.
   * @throws SolrImportExportException if there is a problem in communicating with Solr.
   */
  public void handleExport(final Context context, final String indexName,
    final String fromWhen, final DSpaceRunnableHandler handler)
    throws SolrServerException, IOException, SolrImportExportException, SQLException, AuthorizeException;

}
