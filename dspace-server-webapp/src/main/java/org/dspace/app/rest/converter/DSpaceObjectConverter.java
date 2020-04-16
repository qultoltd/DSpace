/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.converter;

import java.sql.SQLException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.model.MetadataValueList;
import org.dspace.app.rest.projection.Projection;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.app.util.service.MetadataExposureService;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.DSpaceObject;
import org.dspace.content.MetadataField;
import org.dspace.content.MetadataValue;
import org.dspace.core.Context;
import org.dspace.services.RequestService;
import org.dspace.services.model.Request;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * This is the base converter from/to objects in the DSpace API data model and
 * the REST data model
 *
 * @param <M> the Class in the DSpace API data model
 * @param <R> the Class in the DSpace REST data model
 * @author Andrea Bollini (andrea.bollini at 4science.it)
 */
public abstract class DSpaceObjectConverter<M extends DSpaceObject, R extends org.dspace.app.rest.model
        .DSpaceObjectRest> implements DSpaceConverter<M, R> {

    private static final Logger log = LogManager.getLogger(DSpaceObjectConverter.class);

    @Autowired
    ConverterService converter;

    @Autowired
    AuthorizeService authorizeService;

    @Autowired
    MetadataExposureService metadataExposureService;

    @Autowired
    RequestService requestService;


    @Override
    public R convert(M obj, Projection projection) {
        R resource = newInstance();
        resource.setProjection(projection);
        resource.setHandle(obj.getHandle());
        if (obj.getID() != null) {
            resource.setUuid(obj.getID().toString());
        }
        resource.setName(obj.getName());

        MetadataValueList metadataValues = getPermissionFilteredMetadata(getContext(), obj);
        resource.setMetadata(converter.toRest(metadataValues, projection));
        return resource;
    }

    protected abstract R newInstance();


    /**
     * Retrieves the metadata list filtered according to the hidden metadata configuration
     * When the context is null, it will return the metadatalist as for an anonymous user
     * @param context   The context
     * @param obj       The object of which the filtered metadata will be retrieved
     * @return A list of object metadata filtered based on the the hidden metadata configuration
     */
    public MetadataValueList getPermissionFilteredMetadata(Context context, M obj) {
        List<MetadataValue> metadata = obj.getMetadata();
        try {
            if (context != null && authorizeService.isAdmin(context)) {
                return new MetadataValueList(metadata);
            }
            for (MetadataValue mv : metadata) {
                MetadataField metadataField = mv.getMetadataField();
                if (metadataExposureService
                        .isHidden(context, metadataField.getMetadataSchema().getName(),
                                  metadataField.getElement(),
                                  metadataField.getQualifier())) {
                    metadata.remove(mv);
                }
            }
        } catch (SQLException e) {
            log.error("Error filtering metadata based on permissions", e);
        }
        return new MetadataValueList(metadata);
    }

    /**
     * Retrieves the context from the request
     * If not request is found, will return null
     * @return  The context retrieved form the current request or null when no context
     */
    private Context getContext() {
        Request currentRequest = requestService.getCurrentRequest();
        if (currentRequest != null) {
            return ContextUtil.obtainContext(currentRequest.getServletRequest());
        }
        return null;
    }
}