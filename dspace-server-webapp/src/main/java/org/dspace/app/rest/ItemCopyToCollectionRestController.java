package org.dspace.app.rest;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.dspace.app.rest.converter.ConverterService;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.hateoas.CollectionResource;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.app.rest.utils.Utils;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.BundleService;
import org.dspace.content.service.InstallItemService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.rest.webmvc.ControllerUtils;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.dspace.app.rest.utils.RegexUtils.*;
import static org.dspace.core.Constants.*;

/**
 * @author dsipos
 */
@RestController
@RequestMapping("/api/core/items" + REGEX_REQUESTMAPPING_IDENTIFIER_AS_UUID + "/copy")
public class ItemCopyToCollectionRestController {
  @Autowired
  ItemService itemService;

  @Autowired
  BundleService bundleService;

  @Autowired
  ConverterService converter;

  @Autowired
  Utils utils;

  @Autowired
  WorkspaceItemService workspaceItemService;

  @Autowired
  InstallItemService installItemService;

  @Autowired
  BitstreamService bitstreamService;

  /**
   * This method will update the owning collection of the item that correspond to the provided item uuid, effectively
   * moving the item to the new collection.
   * @param uuid     The UUID of the item that will be moved
   * @param response The response object
   * @param request  The request object
   * @return The wrapped resource containing the new owning collection or null when the item was not moved
   * @throws SQLException       If something goes wrong
   * @throws IOException        If something goes wrong
   * @throws AuthorizeException If the user is not authorized to perform the move action
   */

  @PutMapping(consumes = { "text/uri-list" })
  @PostAuthorize("returnObject != null")
  public ResponseEntity<RepresentationModel<?>> copy(@PathVariable UUID uuid, HttpServletResponse response,
    HttpServletRequest request) throws SQLException, IOException, AuthorizeException {
    Context context = ContextUtil.obtainContext(request);
    List<DSpaceObject> dsoList = utils.constructDSpaceObjectList(context, utils.getStringListFromRequest(request));
    if (dsoList.size() != 1 || dsoList.get(0).getType() != COLLECTION) {
      throw new UnprocessableEntityException("The collection doesn't exist " +
        "or the data cannot be resolved to a collection.");
    }

    Collection targetCollection = performItemCopy(context, uuid, (Collection) dsoList.get(0));

    if (targetCollection == null) {
      return null;
    }
    CollectionResource collectionResource =
      converter.toResource(converter.toRest(targetCollection, utils.obtainProjection()));
    return ControllerUtils.toResponseEntity(HttpStatus.OK, new HttpHeaders(), collectionResource);
  }

  /**
   * This method will perform the item copy based on the provided item uuid and the target collection
   * @param context          The context Object
   * @param itemUuid         The uuid of the item to be moved
   * @param targetCollection The target collection
   * @return The new owning collection of the item when authorized or null when not authorized
   * @throws SQLException       If something goes wrong
   * @throws AuthorizeException If the user is not authorized to perform the move action
   */
  private Collection performItemCopy(final Context context, final UUID itemUuid, final Collection targetCollection)
    throws SQLException, AuthorizeException, IOException {

    List<String> unCopyField =
      List.of("dc.date.accessioned", "dc.date.available", "dc.description.provenance", "dc.identifier.uri");

    Item item = itemService.find(context, itemUuid);
    WorkspaceItem workspaceItem = workspaceItemService.create(context, targetCollection, false);
    if (item == null) {
      throw new ResourceNotFoundException("Item with id: " + itemUuid + " not found");
    }
    if (!(item.isArchived() || item.isWithdrawn())) {
      throw new DSpaceBadRequestException("Only archived or withdrawn items can be copied between collections");
    }
    Item newItem = workspaceItem.getItem();
    newItem.setArchived(true);
    newItem.setOwningCollection(targetCollection);
    newItem.setDiscoverable(item.isDiscoverable());
    newItem.setLastModified(item.getLastModified());

    //add copy metadata
    List<MetadataValue> filterMetadata = item.getMetadata().stream()
      .filter(metadataValue -> !unCopyField.contains(metadataValue.getMetadataField().toString('.'))).collect(
        Collectors.toList());
    for (MetadataValue metadataValue : filterMetadata) {
      itemService.addMetadata(context, newItem, metadataValue.getMetadataField(), metadataValue.getLanguage(),
        metadataValue.getValue(), metadataValue.getAuthority(), metadataValue.getConfidence());
    }
    itemService.update(context, newItem);

    //copy bitstreams
    for (Bundle bundle : item.getBundles()) {
      Bundle bnd = bundleService.create(context, newItem, bundle.getName());
      for (Bitstream bitstream : bundle.getBitstreams()) {
        InputStream inputStream = bitstreamService.retrieve(context, bitstream);
        Bitstream newBitstream = bitstreamService.create(context, bnd, inputStream);
        bitstreamService.setFormat(context, newBitstream, bitstream.getFormat(context));
        newBitstream.setName(context, bitstream.getName());
        newBitstream.setDescription(context, bitstream.getDescription());
        bitstreamService.update(context, newBitstream);
      }
      itemService.addBundle(context, newItem, bnd);
    }
    installItemService.installItem(context, workspaceItem);

    context.commit();

    return context.reloadEntity(targetCollection);
  }
}
