# wso2-claim-populator
An OSGI component for populating default claims in the Identity Server by reading from the claim-config.xml across restarts 

## Build

Clone the repository and in the directory where the pom file is located, issue the following command to build the project.
```
mvn clean install
```

## Deploy

After successfully building the project, the resulting jar file can be retrieved from the target directory. (the already built jar is included in the release section) copy the resulting jar to the <IS_HOME>/repository/components/dropins/ directory.

During each server startup this component will verify whether the claims in the claim-config.xml are present in the database, if there are any claims which are not present, they will be added.


```
[2021-07-03 13:09:13,280] []  INFO {org.wso2.custom.claim.populator.FailSafeClaimPopulator} - Fail-Safe Claim Populator activated
[2021-07-03 13:09:13,360] []  INFO {org.wso2.custom.claim.populator.FailSafeClaimPopulator} - Missing local claims    : 0
[2021-07-03 13:09:13,361] []  INFO {org.wso2.custom.claim.populator.FailSafeClaimPopulator} - Missing claim dialects  : 0
[2021-07-03 13:09:13,362] []  INFO {org.wso2.custom.claim.populator.FailSafeClaimPopulator} - Missing external claims : 2
[2021-07-03 13:09:13,362] []  INFO {org.wso2.custom.claim.populator.FailSafeClaimPopulator} - Adding missing claims
```
