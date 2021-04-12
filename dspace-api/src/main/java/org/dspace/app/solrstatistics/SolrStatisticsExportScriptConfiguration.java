/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.solrstatistics;

import java.sql.SQLException;
import org.apache.commons.cli.Options;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.core.Context;
import org.dspace.scripts.configuration.ScriptConfiguration;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The {@link ScriptConfiguration} for the {@link SolrStatisticsExport} script
 */
public class SolrStatisticsExportScriptConfiguration<T extends SolrStatisticsExport> extends ScriptConfiguration<T> {

    @Autowired
    private AuthorizeService authorizeService;

    private Class<T> dspaceRunnableClass;

    @Override
    public Class<T> getDspaceRunnableClass() {
        return dspaceRunnableClass;
    }

    /**
     * Generic setter for the dspaceRunnableClass
     * @param dspaceRunnableClass   The dspaceRunnableClass to be set on this SolrStatisticsExportScriptConfiguration
     */
    @Override
    public void setDspaceRunnableClass(Class<T> dspaceRunnableClass) {
        this.dspaceRunnableClass = dspaceRunnableClass;
    }

    @Override
    public boolean isAllowedToExecute(Context context) {
        try {
            return authorizeService.isAdmin(context);
        } catch (SQLException e) {
            throw new RuntimeException("SQLException occurred when checking if the current user is an admin", e);
        }
    }

    @Override
    public Options getOptions() {
        if (options == null) {
            Options options = new Options();
            options.addOption("a", "all", false, "Export all record from solr statistic");
            options.getOption("a").setType(boolean.class);
            options.addOption("l", "last", true, "When exporting, export records from the last [timeperiod] only." +
              " This can be one of: 'd' (beginning of yesterday through to now);" +
              " 'm' (beginning of the previous month through to end of the previous month);" +
              " a number, in which case the last [number] of days are exported, through to now (use 0 for today's data)" +
              "." +
              " Date calculation is done in UTC. If omitted, all documents are exported.");
            options.getOption("l").setType(String.class);
            options.addOption("h", "help", false, "help");
            options.getOption("h").setType(boolean.class);
            super.options = options;
        }
        return options;
    }

}
