/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authenticate;


import static java.lang.String.format;
import static java.net.URLEncoder.encode;
import static org.apache.commons.lang.BooleanUtils.toBoolean;
import static org.apache.commons.lang3.StringUtils.isAnyBlank;
import static org.apache.commons.lang3.StringUtils.isBlank;

import jakarta.servlet.http.HttpSession;
import java.io.UnsupportedEncodingException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authenticate.oidc.OidcClient;
import org.dspace.authenticate.oidc.model.OidcTokenResponseDTO;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * OpenID Connect Authentication for DSpace.
 *
 * This implementation doesn't allow/needs to register user, which may be holder
 * by the openID authentication server.
 *
 * @link   https://openid.net/developers/specs/
 *
 * @author Luca Giamminonni (luca.giamminonni at 4science.it)
 */
public class OidcAuthenticationBean implements AuthenticationMethod {

    public static final String OIDC_AUTH_ATTRIBUTE = "oidc";

    private final static String LOGIN_PAGE_URL_FORMAT = "%s?client_id=%s&response_type=code&scope=%s&redirect_uri=%s";

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String OIDC_AUTHENTICATED = "oidc.authenticated";

    private static final String OIDC_GROUPS_ATTRIBUTE = "X-OIDC-GROUPS";

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private OidcClient oidcClient;

    @Autowired
    private EPersonService ePersonService;

    protected GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();

    @Override
    public boolean allowSetPassword(Context context, HttpServletRequest request, String username) throws SQLException {
        return false;
    }

    @Override
    public boolean isImplicit() {
        return false;
    }

    @Override
    public boolean canSelfRegister(Context context, HttpServletRequest request, String username) throws SQLException {
        return canSelfRegister();
    }

    @Override
    public void initEPerson(Context context, HttpServletRequest request, EPerson eperson) throws SQLException {
    }

    public void setSpecialGroups(Context context, List<String> groupList) {
        if (groupList.isEmpty()) {
            return;
        }
        LOGGER.info("Setting groups for user");
        Set<Group> groupSet = new HashSet<>();
        for (String group : groupList) {
            String[] groupNames = configurationService
                .getArrayProperty("authentication-oidc.role-mapping." + group);
            if (groupNames == null || groupNames.length == 0) {
                groupNames = configurationService
                    .getArrayProperty("authentication-oidc.role-mapping." + group.toLowerCase());
            }
            if (groupNames == null || groupNames.length == 0) {
                continue;
            }

            for (final String groupName : groupNames) {
                try {
                    Group groupObj = groupService.findByName(context, groupName.trim());
                    if (groupObj != null) {
                        groupSet.add(groupObj);
                    } else {
                        LOGGER.debug("Unable to find group: '{}'", groupName.trim());
                    }
                } catch (SQLException sqle) {
                    LOGGER.error("Exception thrown while trying to lookup affiliation role for " +
                      "group name: '{}'", groupName.trim(), sqle);
                }
            }
        }
        for (Group group : new ArrayList<>(groupSet)) {
            context.setSpecialGroup(group.getID());
        }
    }

    @Override
    public List getSpecialGroups(Context context, HttpServletRequest request) throws SQLException {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            LOGGER.info("Bearer present (len={}): {}…{}", auth.length(),
                auth.substring(0, 20), auth.substring(Math.max(0, auth.length() - 10)));
        } else {
            LOGGER.info("No Authorization header on this request.");
        }
        try {
            if (request == null ||
                context.getCurrentUser() == null) {
                return Collections.emptyList();
            }

            if (!context.getSpecialGroups().isEmpty() ) {
                LOGGER.debug("Returning cached special groups.");
                return context.getSpecialGroups();
            }

            LOGGER.debug("Starting to determine special groups");
            String[] defaultRoles = configurationService.getArrayProperty("authentication-oidc.default-roles");
            String roleHeader = configurationService.getProperty("authentication-oidc.role-header");
            if (StringUtils.isBlank(roleHeader)) {
                roleHeader = OIDC_GROUPS_ATTRIBUTE;
            }
            boolean ignoreScope = configurationService
                .getBooleanProperty("authentication-oidc.role-header.ignore-scope", true);
            boolean ignoreValue = configurationService
                .getBooleanProperty("authentication-oidc.role-header.ignore-value", false);

            if (ignoreScope && ignoreValue) {
                throw new IllegalStateException(
                  "Both config parameters for ignoring an roll attributes scope and value are turned on, this is " +
                        "not a permissable configuration. (Note: ignore-scope defaults to true) The configuration " +
                        "parameters are: 'authentication-oidc.role-header.ignore-scope' " +
                        "and 'authentication-oidc.role-header.ignore-value'");
            }

            // Get the Shib supplied affiliation or use the default affiliation
            List<String> affiliations = findMultipleAttributes(request, roleHeader);
            if (affiliations.isEmpty()) {
                if (defaultRoles != null) {
                    affiliations = Arrays.asList(defaultRoles);
                }
                LOGGER.debug("Failed to find oidc role header, '{}', falling back to the default roles: '{}'",
                    roleHeader, StringUtils.join(defaultRoles, ","));
            } else {
                LOGGER.debug("Found oidc role header: '{}' = '{}'", roleHeader, affiliations);
            }

            // Loop through each affiliation
            Set<Group> groups = new HashSet<>();
            if (!affiliations.isEmpty()) {
                for (String affiliation : affiliations) {
                    // If we ignore the affiliation's scope then strip the scope if it exists.
                    if (ignoreScope) {
                        int index = affiliation.indexOf('@');
                        if (index != -1) {
                            affiliation = affiliation.substring(0, index);
                        }
                    }
                    // If we ignore the value, then strip it out so only the scope remains.
                    if (ignoreValue) {
                        int index = affiliation.indexOf('@');
                        if (index != -1) {
                            affiliation = affiliation.substring(index + 1);
                        }
                    }

                    // Get the group names
                    String[] groupNames = configurationService
                      .getArrayProperty("authentication-oidc.role-mapping." + affiliation);
                    if (groupNames == null || groupNames.length == 0) {
                        groupNames = configurationService
                          .getArrayProperty("authentication-oidc.role-mapping." + affiliation.toLowerCase());
                    }

                    if (groupNames == null) {
                        LOGGER.debug("Unable to find role mapping for the value, '{}', there should be a mapping " +
                            "in config/modules/authentication-oidc.cfg:  role. {} = <some group name>",
                            affiliation, affiliation);
                        continue;
                    } else {
                        LOGGER.debug("Mapping role affiliation to DSpace group: '{}'", StringUtils.join(groupNames, ","));
                    }

                    // Add each group to the list.
                    for (final String groupName : groupNames) {
                        try {
                            Group group = groupService.findByName(context, groupName.trim());
                            if (group != null) {
                                groups.add(group);
                            } else {
                                LOGGER.debug("Unable to find group: '{}'", groupName.trim());
                            }
                        } catch (SQLException sqle) {
                            LOGGER.error("Exception thrown while trying to lookup affiliation role for " +
                                "group name: '{}'", groupName.trim(), sqle);
                        }
                    }
                }
            }


            LOGGER.info("Added current EPerson to special groups: {}", groups);

            return new ArrayList<>(groups);

        } catch (Exception e) {
            LOGGER.error("Unable to validate any sepcial groups this user may belong too because of an exception.", e);
            return Collections.emptyList();
        }
    }

    protected List<String> findMultipleAttributes(HttpServletRequest request, String name) {
        String values = findAttribute(request, name);

        if (values == null) {
            return Collections.emptyList();
        }

        List<String> valueList = new ArrayList<>();
        int idx = 0;
        do {
            idx = values.indexOf(';', idx);

            if (idx == 0) {
                // if the string starts with a semicolon just remove it. This will
                // prevent an endless loop in an error condition.
                values = values.substring(1, values.length());

            } else if (idx > 0 && values.charAt(idx - 1) == '\\') {
                // The attribute starts with an escaped semicolon
                idx++;
            } else if (idx > 0) {
                // First extract the value and store it on the list.
                String value = values.substring(0, idx);
                value = value.replaceAll("\\\\;", ";");
                valueList.add(value);

                // Next, remove the value from the string and continue to scan.
                values = values.substring(idx + 1, values.length());
                idx = 0;
            }
        } while (idx >= 0);

        // The last attribute will still be left on the values string, put it
        // into the list.
        if (!values.isEmpty()) {
            values = values.replaceAll("\\\\;", ";");
            valueList.add(values);
        }

        return valueList;
    }

    protected String findAttribute(HttpServletRequest request, String name) {
        if (name == null) {
            return null;
        }
        // First try to get the value from the attribute
        String value = (String) request.getAttribute(name);
        if (StringUtils.isEmpty(value)) {
            value = (String) request.getAttribute(name.toLowerCase());
        }
        if (StringUtils.isEmpty(value)) {
            value = (String) request.getAttribute(name.toUpperCase());
        }

        // Second try to get the value from the header
        if (StringUtils.isEmpty(value)) {
            value = request.getHeader(name);
        }

        if (StringUtils.isEmpty(value)) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                Object sv = session.getAttribute(name);
                if (sv != null) {
                    value = String.valueOf(sv);
                }
                if (StringUtils.isEmpty(value)) {
                    sv = session.getAttribute(name.toLowerCase());
                    if (sv != null) {
                        value = String.valueOf(sv);
                    }
                }
                if (StringUtils.isEmpty(value)) {
                    sv = session.getAttribute(name.toUpperCase());
                    if (sv != null) {
                        value = String.valueOf(sv);
                    }
                }
            }
        }

        if (StringUtils.isEmpty(value)) {
            value = request.getHeader(name.toLowerCase());
        }
        if (StringUtils.isEmpty(value)) {
            value = request.getHeader(name.toUpperCase());
        }

        // Added extra check for empty value of an attribute.
        // In case that value is Empty, it should not be returned, return 'null' instead.
        // This prevents passing empty value to other methods, stops the authentication process
        // and prevents creation of 'empty' DSpace EPerson if autoregister == true and it subsequent
        // authentication.
        if (StringUtils.isEmpty(value)) {
            LOGGER.debug("OidcAuthentication - attribute {} is empty!", name);
            return null;
        }

        boolean reconvertAttributes =
            configurationService.getBooleanProperty("authentication-oidc.reconvert.attributes", false);

        if (!StringUtils.isEmpty(value) && reconvertAttributes) {
            try {
                value = new String(value.getBytes("ISO-8859-1"), "UTF-8");
            } catch (UnsupportedEncodingException ex) {
                LOGGER.warn("Failed to reconvert oidc attribute ({}).", name, ex);
            }
        }

        return value;
    }

    @Override
    public String getName() {
        return OIDC_AUTH_ATTRIBUTE;
    }

    @Override
    public int authenticate(Context context, String username, String password, String realm, HttpServletRequest request)
        throws SQLException {

        if (request == null) {
            LOGGER.warn("Unable to authenticate using OIDC because the request object is null.");
            return BAD_ARGS;
        }

        if (request.getAttribute(OIDC_AUTH_ATTRIBUTE) == null) {
            return NO_SUCH_USER;
        }

        String code = (String) request.getParameter("code");
        if (StringUtils.isEmpty(code)) {
            LOGGER.warn("The incoming request has not code parameter");
            return NO_SUCH_USER;
        }

        return authenticateWithOidc(context, code, request);
    }

    private int authenticateWithOidc(Context context, String code, HttpServletRequest request) throws SQLException {

        OidcTokenResponseDTO accessToken = getOidcAccessToken(code);
        if (accessToken == null) {
            LOGGER.warn("No access token retrieved by code");
            return NO_SUCH_USER;
        }

        Map<String, Object> userInfo = getOidcUserInfo(accessToken.getAccessToken());

        String groupsClaimName =
          configurationService.getProperty("authentication-oidc.user-info.groups-claim", "groups");
        String groupsJoined = extractGroupsAsSemicolonString(userInfo.get(groupsClaimName));
        if (StringUtils.isNotBlank(groupsJoined)) {
            request.setAttribute(OIDC_GROUPS_ATTRIBUTE, groupsJoined);
            HttpSession session = request.getSession(true);
            session.setAttribute(OIDC_GROUPS_ATTRIBUTE, groupsJoined);
            LOGGER.debug("OIDC groups claim '{}' found -> {}", groupsClaimName, groupsJoined);
        } else {
            LOGGER.debug("No OIDC groups found under claim '{}'", groupsClaimName);
        }

        String email = getAttributeAsString(userInfo, getEmailAttribute());
        if (StringUtils.isBlank(email)) {
            LOGGER.warn("No email found in the user info attributes");
            return NO_SUCH_USER;
        }

        EPerson ePerson = ePersonService.findByEmail(context, email);
        if (ePerson != null) {
            request.setAttribute(OIDC_AUTHENTICATED, true);
            HttpSession session = request.getSession(true);
            session.setAttribute(OIDC_GROUPS_ATTRIBUTE, groupsJoined);
            int result = ePerson.canLogIn() ? logInEPerson(context, ePerson) : BAD_ARGS;
            LOGGER.info("Check user groups");
            if (result == SUCCESS && userInfo.get(groupsClaimName) instanceof List) {
                LOGGER.info("Group list valid");
                List<String> groupList = (List<String>) userInfo.get(groupsClaimName);
                setSpecialGroups(context, groupList);
            }
            return result;
        }

        // if self registration is disabled, warn about this failure to find a matching eperson
        if (! canSelfRegister()) {
            LOGGER.warn("Self registration is currently disabled for OIDC, and no ePerson could be found for email: {}",
                email);
        }
        int result =  canSelfRegister() ? registerNewEPerson(context, userInfo, email) : NO_SUCH_USER;
        LOGGER.info("Check user groups");
        if (result == SUCCESS && userInfo.get(groupsClaimName) instanceof List) {
            LOGGER.info("Group list valid");
            List<String> groupList = (List<String>) userInfo.get(groupsClaimName);
            setSpecialGroups(context, groupList);
        }
        return result;
    }

    private String extractGroupsAsSemicolonString(Object claimVal) {
        if (claimVal == null) {
            return null;
        }
        if (claimVal instanceof List) {
            List<?> list = (List<?>) claimVal;
            if (list.isEmpty()) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return String.join(";", out);
        }
        String s = String.valueOf(claimVal).trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1).replace("\"", "").replace("'", "").trim();
            if (s.isEmpty()) {
                return null;
            }
            s = s.replaceAll("\\s*,\\s*", ";");
            return s;
        }
        if (s.contains(",")) {
            return s.replaceAll("\\s*,\\s*", ";");
        }
        return s;
    }

    @Override
    public String loginPageURL(Context context, HttpServletRequest request, HttpServletResponse response) {

        String authorizeUrl = configurationService.getProperty("authentication-oidc.authorize-endpoint");
        String clientId = configurationService.getProperty("authentication-oidc.client-id");
        String clientSecret = configurationService.getProperty("authentication-oidc.client-secret");
        String redirectUri = configurationService.getProperty("authentication-oidc.redirect-url");
        String tokenUrl = configurationService.getProperty("authentication-oidc.token-endpoint");
        String userInfoUrl = configurationService.getProperty("authentication-oidc.user-info-endpoint");
        String[] defaultScopes =
            new String[] {
                "openid", "email", "profile"
            };
        String scopes = String.join(" ", configurationService.getArrayProperty("authentication-oidc.scopes",
            defaultScopes));

        if (isAnyBlank(authorizeUrl, clientId, redirectUri, clientSecret, tokenUrl, userInfoUrl)) {
            LOGGER.error("Missing mandatory configuration properties for OidcAuthenticationBean");

            // prepare a Map of the properties which can not have sane defaults, but are still required
            final Map<String, String> map = Map.of("authorizeUrl", authorizeUrl, "clientId", clientId, "redirectUri",
                redirectUri, "clientSecret", clientSecret, "tokenUrl", tokenUrl, "userInfoUrl", userInfoUrl);
            final Iterator<Entry<String, String>> iterator = map.entrySet().iterator();

            while (iterator.hasNext()) {
                final Entry<String, String> entry = iterator.next();

                if (isBlank(entry.getValue())) {
                    LOGGER.error(" * {} is missing", entry::getKey);
                }
            }
            return "";
        }

        try {
            return format(LOGIN_PAGE_URL_FORMAT, authorizeUrl, clientId, scopes, encode(redirectUri, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            LOGGER.error(e::getMessage, e);
            return "";
        }

    }

    private int logInEPerson(Context context, EPerson ePerson) {
        context.setCurrentUser(ePerson);
        return SUCCESS;
    }

    private int registerNewEPerson(Context context, Map<String, Object> userInfo, String email) throws SQLException {
        try {

            context.turnOffAuthorisationSystem();

            EPerson eperson = ePersonService.create(context);

            eperson.setNetid(email);
            eperson.setEmail(email);

            String firstName = getAttributeAsString(userInfo, getFirstNameAttribute());
            if (firstName != null) {
                eperson.setFirstName(context, firstName);
            }

            String lastName = getAttributeAsString(userInfo, getLastNameAttribute());
            if (lastName != null) {
                eperson.setLastName(context, lastName);
            }

            eperson.setCanLogIn(true);
            eperson.setSelfRegistered(true);

            ePersonService.update(context, eperson);
            context.setCurrentUser(eperson);
            context.dispatchEvents();

            return SUCCESS;

        } catch (Exception ex) {
            LOGGER.error("An error occurs registering a new EPerson from OIDC", ex);
            return NO_SUCH_USER;
        } finally {
            context.restoreAuthSystemState();
        }
    }

    private OidcTokenResponseDTO getOidcAccessToken(String code) {
        try {
            return oidcClient.getAccessToken(code);
        } catch (Exception ex) {
            LOGGER.error("An error occurs retriving the OIDC access_token", ex);
            return null;
        }
    }

    private Map<String, Object> getOidcUserInfo(String accessToken) {
        try {
            return oidcClient.getUserInfo(accessToken);
        } catch (Exception ex) {
            LOGGER.error("An error occurs retriving the OIDC user info", ex);
            return Map.of();
        }
    }

    private String getAttributeAsString(Map<String, Object> userInfo, String attribute) {
        if (isBlank(attribute)) {
            return null;
        }
        return userInfo.containsKey(attribute) ? String.valueOf(userInfo.get(attribute)) : null;
    }

    private String getEmailAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.email", "email");
    }

    private String getFirstNameAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.first-name", "given_name");
    }

    private String getLastNameAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.last-name", "family_name");
    }

    private boolean canSelfRegister() {
        String canSelfRegister = configurationService.getProperty("authentication-oidc.can-self-register", "true");
        if (isBlank(canSelfRegister)) {
            return true;
        }
        return toBoolean(canSelfRegister);
    }

    public OidcClient getOidcClient() {
        return this.oidcClient;
    }

    public void setOidcClient(OidcClient oidcClient) {
        this.oidcClient = oidcClient;
    }

    @Override
    public boolean isUsed(final Context context, final HttpServletRequest request) {
        if (request != null &&
                context.getCurrentUser() != null &&
                request.getAttribute(OIDC_AUTHENTICATED) != null) {
            return true;
        }
        return false;
    }

    @Override
    public boolean canChangePassword(Context context, EPerson ePerson, String currentPassword) {
        return false;
    }

}
