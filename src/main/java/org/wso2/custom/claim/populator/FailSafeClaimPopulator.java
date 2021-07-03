package org.wso2.custom.claim.populator;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.identity.claim.metadata.mgt.dao.CacheBackedClaimDialectDAO;
import org.wso2.carbon.identity.claim.metadata.mgt.dao.CacheBackedExternalClaimDAO;
import org.wso2.carbon.identity.claim.metadata.mgt.dao.CacheBackedLocalClaimDAO;
import org.wso2.carbon.identity.claim.metadata.mgt.dao.ClaimDialectDAO;
import org.wso2.carbon.identity.claim.metadata.mgt.dao.ExternalClaimDAO;
import org.wso2.carbon.identity.claim.metadata.mgt.dao.LocalClaimDAO;
import org.wso2.carbon.identity.claim.metadata.mgt.exception.ClaimMetadataException;
import org.wso2.carbon.identity.claim.metadata.mgt.model.AttributeMapping;
import org.wso2.carbon.identity.claim.metadata.mgt.model.ClaimDialect;
import org.wso2.carbon.identity.claim.metadata.mgt.model.ExternalClaim;
import org.wso2.carbon.identity.claim.metadata.mgt.model.LocalClaim;
import org.wso2.carbon.identity.claim.metadata.mgt.util.ClaimConstants;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.claim.ClaimKey;
import org.wso2.carbon.user.core.claim.ClaimManagerFactory;
import org.wso2.carbon.user.core.claim.inmemory.ClaimConfig;
import org.wso2.carbon.user.core.claim.inmemory.FileBasedClaimBuilder;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

@Component(
        name = "org.wso2.custom.claim.populator",
        immediate = true
)
public class FailSafeClaimPopulator {
    private static final Log log = LogFactory.getLog(FailSafeClaimPopulator.class);

    private static ClaimConfig claimConfig;

    private ClaimDialectDAO claimDialectDAO = new CacheBackedClaimDialectDAO();
    private CacheBackedLocalClaimDAO localClaimDAO = new CacheBackedLocalClaimDAO(new LocalClaimDAO());
    private CacheBackedExternalClaimDAO externalClaimDAO = new CacheBackedExternalClaimDAO(new ExternalClaimDAO());

    static {
        try {
            claimConfig = FileBasedClaimBuilder.buildClaimMappingsFromConfigFile();
        } catch (IOException e) {
            log.error("Could not find claim configuration file", e);
        } catch (XMLStreamException e) {
            log.error("Error while parsing claim configuration file", e);
        } catch (UserStoreException e) {
            log.error("Error while initializing claim manager");
        }
    }

    @Activate
    protected void activate(ComponentContext context) {

        log.info("Fail-Safe Claim Populator activated");

        verifyAddDefaultClaims(claimConfig, -1234);
    }

    private void verifyAddDefaultClaims(ClaimConfig claimConfig, int tenantId) {

        if (claimConfig.getClaimMap() != null) {

            String primaryDomainName = IdentityUtil.getPrimaryDomainName();

            // Adding external dialects and claims
            Set<String> claimDialectList = new HashSet<>();

            //find missing local claims
            List<LocalClaim> localClaimsFromFile = new ArrayList<>();

            for (Map.Entry<ClaimKey, org.wso2.carbon.user.core.claim.ClaimMapping> entry : claimConfig.getClaimMap()
                    .entrySet()) {

                ClaimKey claimKey = entry.getKey();
                org.wso2.carbon.user.core.claim.ClaimMapping claimMapping = entry.getValue();
                String claimDialectURI = claimMapping.getClaim().getDialectURI();
                String claimURI = claimKey.getClaimUri();

                if (ClaimConstants.LOCAL_CLAIM_DIALECT_URI.equalsIgnoreCase(claimDialectURI)) {

                    List<AttributeMapping> mappedAttributes = new ArrayList<>();
                    if (StringUtils.isNotBlank(claimMapping.getMappedAttribute())) {
                        mappedAttributes
                                .add(new AttributeMapping(primaryDomainName, claimMapping.getMappedAttribute()));
                    }

                    if (claimMapping.getMappedAttributes() != null) {
                        for (Map.Entry<String, String> claimMappingEntry : claimMapping.getMappedAttributes()
                                .entrySet()) {
                            mappedAttributes.add(new AttributeMapping(claimMappingEntry.getKey(),
                                    claimMappingEntry.getValue()));
                        }
                    }

                    LocalClaim localClaim = new LocalClaim(claimURI, mappedAttributes,
                            fillClaimProperties(claimConfig, claimKey));
                    localClaimsFromFile.add(localClaim);

                } else {
                    claimDialectList.add(claimDialectURI);
                }
            }

            try {
                localClaimDAO.getLocalClaims(tenantId).forEach(claim ->
                        localClaimsFromFile.removeIf(x -> x.getClaimURI().equals(claim.getClaimURI())));
            } catch (ClaimMetadataException e) {
                log.error("Error while retrieving local claims", e);
            }

            //find missing dialects
            List<ClaimDialect> claimDialectsFromFile = new ArrayList<>();

            // Add external claim dialects
            for (String claimDialectURI : claimDialectList) {
                ClaimDialect claimDialect = new ClaimDialect(claimDialectURI);
                claimDialectsFromFile.add(claimDialect);
            }

            List<ClaimDialect> claimDialectsFromDB = null;
            try {
                claimDialectsFromDB = claimDialectDAO.getClaimDialects(tenantId);
                claimDialectsFromDB.forEach(dialect ->
                        claimDialectsFromFile.removeIf(x -> x.getClaimDialectURI().equals(dialect.getClaimDialectURI())));
            } catch (ClaimMetadataException e) {
                log.error("Error while retrieving claim dialects", e);
            }

            //find missing external claims
            List<ExternalClaim> externalClaimsFromFile = new ArrayList<>();

            for (Map.Entry<ClaimKey, org.wso2.carbon.user.core.claim.ClaimMapping> entry : claimConfig.getClaimMap()
                    .entrySet()) {

                ClaimKey claimKey = entry.getKey();
                String claimURI = claimKey.getClaimUri();

                String claimDialectURI = entry.getValue().getClaim().getDialectURI();

                if (!ClaimConstants.LOCAL_CLAIM_DIALECT_URI.equalsIgnoreCase(claimDialectURI)) {

                    String mappedLocalClaimURI = claimConfig.getPropertyHolderMap().get(claimKey).get(ClaimConstants
                            .MAPPED_LOCAL_CLAIM_PROPERTY);
                    ExternalClaim externalClaim = new ExternalClaim(claimDialectURI, claimURI, mappedLocalClaimURI,
                            fillClaimProperties(claimConfig, claimKey));
                    externalClaimsFromFile.add(externalClaim);
                }
            }
            for( ClaimDialect claimDialect : claimDialectsFromDB ) {
                try {
                    externalClaimDAO.getExternalClaims(claimDialect.getClaimDialectURI(), tenantId).forEach(claim ->
                            externalClaimsFromFile.removeIf(x -> x.getClaimURI().equals(claim.getClaimURI())));
                } catch (ClaimMetadataException e) {
                    log.error("Error while retrieving external claims", e);
                }
            }
            log.info("Missing local claims    : " + localClaimsFromFile.size());
            log.info("Missing claim dialects  : " + claimDialectsFromFile.size());
            log.info("Missing external claims : " + externalClaimsFromFile.size());

            if (localClaimsFromFile.size() > 0 || claimDialectsFromFile.size() > 0
                    || externalClaimsFromFile.size() > 0 ) {
                log.info("Adding missing claims");
                addLocalClaims(localClaimsFromFile, tenantId);
                addClaimDialects(claimDialectsFromFile, tenantId);
                addExternalClaims(externalClaimsFromFile, tenantId);
            }

        }
    }

    private void addLocalClaims(List<LocalClaim> localClaims, int tenantId) {
        for(LocalClaim localClaim : localClaims) {
            try {
                localClaimDAO.addLocalClaim(localClaim, tenantId);
            } catch (ClaimMetadataException e) {
                log.error("Error while adding missing local claim :" + localClaim.getClaimURI(), e);
                continue;
            }
        }
    }

    private void addClaimDialects(List<ClaimDialect> claimDialects, int tenantId) {
        for(ClaimDialect claimDialect : claimDialects) {
            try {
                claimDialectDAO.addClaimDialect(claimDialect, tenantId);
            } catch (ClaimMetadataException e) {
                log.error("Error while adding missing claim dialect :" + claimDialect.getClaimDialectURI(), e);
                continue;
            }
        }
    }

    private void addExternalClaims(List<ExternalClaim> externalClaims, int tenantId) {
        for(ExternalClaim externalClaim : externalClaims) {
            try {
                externalClaimDAO.addExternalClaim(externalClaim, tenantId);
            } catch (ClaimMetadataException e) {
                log.error("Error while adding missing external claim " + externalClaim.getClaimURI() + " to dialect "
                                + externalClaim.getClaimDialectURI(), e);
            }
        }
    }

    private Map<String, String> fillClaimProperties(ClaimConfig claimConfig, ClaimKey claimKey) {
        Map<String, String> claimProperties = claimConfig.getPropertyHolderMap().get(claimKey);
        claimProperties.remove(ClaimConstants.DIALECT_PROPERTY);
        claimProperties.remove(ClaimConstants.CLAIM_URI_PROPERTY);
        claimProperties.remove(ClaimConstants.ATTRIBUTE_ID_PROPERTY);

        if (!claimProperties.containsKey(ClaimConstants.DISPLAY_NAME_PROPERTY)) {
            claimProperties.put(ClaimConstants.DISPLAY_NAME_PROPERTY, "0");
        }

        if (claimProperties.containsKey(ClaimConstants.SUPPORTED_BY_DEFAULT_PROPERTY)) {
            if (StringUtils.isBlank(claimProperties.get(ClaimConstants.SUPPORTED_BY_DEFAULT_PROPERTY))) {
                claimProperties.put(ClaimConstants.SUPPORTED_BY_DEFAULT_PROPERTY, "true");
            }
        }

        if (claimProperties.containsKey(ClaimConstants.READ_ONLY_PROPERTY)) {
            if (StringUtils.isBlank(claimProperties.get(ClaimConstants.READ_ONLY_PROPERTY))) {
                claimProperties.put(ClaimConstants.READ_ONLY_PROPERTY, "true");
            }
        }

        if (claimProperties.containsKey(ClaimConstants.REQUIRED_PROPERTY)) {
            if (StringUtils.isBlank(claimProperties.get(ClaimConstants.REQUIRED_PROPERTY))) {
                claimProperties.put(ClaimConstants.REQUIRED_PROPERTY, "true");
            }
        }
        return claimProperties;
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {

        log.info("Fail-Safe Claim Populator deactivated");
    }

    @Reference(name = "claim.mgt.component", cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC, unbind = "unsetClaimManagerFactory")
    protected void setClaimManagerFactory(ClaimManagerFactory claimManagerFactory) {
        /* reference service to guarantee that this component will wait until the claims are populated to database */
    }

    protected void unsetClaimManagerFactory(ClaimManagerFactory claimManagerFactory) {
        /* reference service to guarantee that this component will wait until the claims are populated to database */
    }
}
