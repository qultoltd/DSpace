package org.dspace.content.authority;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CommonParams;
import org.dspace.discovery.SolrSearchCore;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;

public class LocalMetadataLookup implements ChoiceAuthority {

  private static final Logger log = LogManager.getLogger(LocalMetadataLookup.class);

  private String pluginInstanceName;

  private String field;

  protected final ConfigurationService configurationService
    = DSpaceServicesFactory.getInstance().getConfigurationService();

  @Override
  public Choices getMatches(String text, int start, int limit, String locale) {
    SolrQuery solrQuery = new SolrQuery();
    if (text == null || text.trim().equals("")) {
      solrQuery.setQuery("*:*");
    } else {
      String query = toQuery(field, text);
      solrQuery.setQuery(query);
    }

    solrQuery.set(CommonParams.START, start);

    int maxNumberOfSolrResults = limit + 1;
    solrQuery.set(CommonParams.ROWS, maxNumberOfSolrResults);

    Choices result;

    try {
      int max = 0;
      boolean hasMore = false;
      SolrSearchCore solrSearchCore = getSearchCore();
      QueryResponse searchResponse = solrSearchCore.getSolr().query(solrQuery, solrSearchCore.REQUEST_METHOD);
      SolrDocumentList authDocs = searchResponse.getResults();
      ArrayList<Choice> choices = new ArrayList<>();
      if (authDocs != null) {
        max = (int) searchResponse.getResults().getNumFound();
        int maxDocs = authDocs.size();
        if (limit < maxDocs) {
          maxDocs = limit;
        }

        for (int i = 0; i < maxDocs; i++) {
          SolrDocument solrDocument = authDocs.get(i);

          if (solrDocument != null) {
            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) solrDocument.getFieldValue(field);
            values.stream()
              .filter(val -> val.toLowerCase().contains(text.toLowerCase()))
              .forEach(val -> {
                if (choices.stream().noneMatch(c -> val.equals(c.authority))) {
                  choices.add(new Choice(val, val,
                    val)); // in this case, the authority is not taken into account, authority, value and the label are all the same
                }
              });
          }
        }
        hasMore = true;
      }

      int confidence;
      if (choices.isEmpty()) {
        confidence = Choices.CF_NOTFOUND;
      } else if (choices.size() == 1) {
        confidence = Choices.CF_UNCERTAIN;
      } else {
        confidence = Choices.CF_AMBIGUOUS;
      }

      result = new Choices(
        choices.stream().sorted(Comparator.comparing(c -> c.label)).toList().toArray(new Choice[choices.size()]),
        start,
        hasMore ? max : choices.size() + start,
        confidence,
        hasMore
      );
    } catch (IOException | SolrServerException e) {
      log.error("Error while retrieving values {field: " + field + ", prefix:" + text + "}", e);
      result = new Choices(true);
    }

    return result;
  }

  @Override
  public Choices getBestMatch(String text, String locale) {
    Choices matches = getMatches(text, 0, 1, locale);
    if (matches.values.length != 0 && !matches.values[0].value.equalsIgnoreCase(text)) {
      matches = new Choices(false);
    }
    return matches;
  }

  @Override
  public String getLabel(String key, String locale) {
    Choice match = getMatches(key, 0, 1, locale).values[0];
    return match.label;
  }

  @Override
  public String getPluginInstanceName() {
    return pluginInstanceName;
  }

  @Override
  public void setPluginInstanceName(String name) {
    this.pluginInstanceName = name;
    for (Map.Entry conf : configurationService.getProperties().entrySet()) {
      if (StringUtils.startsWith((String) conf.getKey(), ChoiceAuthorityServiceImpl.CHOICES_PLUGIN_PREFIX)
        && StringUtils.equals((String) conf.getValue(), name)) {
        field = ((String) conf.getKey()).substring(ChoiceAuthorityServiceImpl.CHOICES_PLUGIN_PREFIX.length());
        // exit the look immediately as we have found it
        break;
      }
    }
  }

  private String toQuery(String searchField, String text) {
    return searchField + ":" + text.toLowerCase().replaceAll(":", "\\\\:") + "*";
  }

  public static SolrSearchCore getSearchCore() {
    return DSpaceServicesFactory
      .getInstance()
      .getServiceManager()
      .getServicesByType(SolrSearchCore.class)
      .get(0);
  }
}
