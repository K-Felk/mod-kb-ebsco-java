package org.folio.service.accesstypes;

import static org.folio.rest.tools.utils.TenantTool.tenantId;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.ws.rs.BadRequestException;

import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import org.folio.repository.RecordType;
import org.folio.repository.accesstypes.AccessTypesOldRepository;
import org.folio.rest.jaxrs.model.AccessTypeCollection;
import org.folio.rest.jaxrs.model.AccessType;
import org.folio.rest.jaxrs.model.AccessTypePostRequest;
import org.folio.rest.jaxrs.model.UserDisplayInfo;
import org.folio.service.exc.ServiceExceptions;
import org.folio.service.userlookup.UserLookUp;
import org.folio.service.userlookup.UserLookUpService;

@Primary
@Component("oldAccessTypesService")
public class AccessTypesServiceOldImpl implements AccessTypesService {

  private static final String HAS_ASSIGNED_RECORDS_MESSAGE = "Can't delete access type that has assigned records";

  @Autowired
  private UserLookUpService userLookUpService;
  @Autowired
  private AccessTypeMappingsService mappingService;
  @Autowired
  private AccessTypesOldRepository repository;
  @Autowired
  private Converter<List<AccessType>, AccessTypeCollection> accessTypeCollectionConverter;

  @Value("${kb.ebsco.credentials.access.types.limit}")
  private int defaultAccessTypesMaxValue;

  @Override
  public CompletableFuture<AccessTypeCollection> findByUser(Map<String, String> okapiHeaders) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<AccessTypeCollection> findByCredentialsId(String credentialsId,
                                                                               Map<String, String> okapiHeaders) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<AccessTypeCollection> findByNames(Collection<String> accessTypeNames,
                                                             Map<String, String> okapiHeaders) {
    return repository.findByNames(accessTypeNames, tenantId(okapiHeaders))
      .thenApply(accessTypeCollectionConverter::convert);
  }

  @Override
  public CompletableFuture<AccessType> findById(String id, Map<String, String> okapiHeaders) {
    return repository.findById(id, tenantId(okapiHeaders))
      .thenApply(getAccessTypeOrFail(id))
      .thenCombine(mappingService.findByAccessTypeId(id, okapiHeaders), (accessType, accessTypeMappings) -> {
        accessType.setUsageNumber(accessTypeMappings.size());
        return accessType;
      });
  }

  @Override
  public CompletableFuture<AccessType> findByRecord(String recordId, RecordType recordType,
                                                                  Map<String, String> okapiHeaders) {
    return mappingService.findByRecord(recordId, recordType, okapiHeaders)
      .thenCompose(mapping -> {
        CompletableFuture<Optional<AccessType>> future = repository.findById(mapping.getAccessTypeId(),
          tenantId(okapiHeaders));

        return future.thenApply(getAccessTypeOrFail(mapping.getAccessTypeId()));
      });
  }

  @Override
  public CompletableFuture<AccessType> save(String credentialsId, AccessTypePostRequest postRequest,
                                            Map<String, String> okapiHeaders) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> update(String id, AccessType accessType, Map<String, String> okapiHeaders) {
    return userLookUpService.getUserInfo(okapiHeaders)
      .thenCompose(updaterUser -> {
        accessType.setUpdater(getUserDisplayInfo(updaterUser));
        accessType.getMetadata().setUpdatedByUsername(updaterUser.getUsername());
        return repository.update(id, accessType, tenantId(okapiHeaders));
      });
  }

  @Override
  public CompletableFuture<Void> deleteById(String id, Map<String, String> okapiHeaders) {
    return hasMappings(id, okapiHeaders)
      .thenAccept(hasMappings -> {
        if (BooleanUtils.isTrue(hasMappings)) {
          throw new BadRequestException(HAS_ASSIGNED_RECORDS_MESSAGE);
        }
      })
      .thenCompose(aVoid -> repository.delete(id, tenantId(okapiHeaders)));
  }

  private CompletableFuture<Boolean> hasMappings(String id, Map<String, String> okapiHeaders) {
    return mappingService.findByAccessTypeId(id, okapiHeaders)
      .thenApply(accessTypeMappings -> !accessTypeMappings.isEmpty());
  }

  private UserDisplayInfo getUserDisplayInfo(UserLookUp userLookUp) {
    final UserDisplayInfo userDisplayInfo = new UserDisplayInfo();
    userDisplayInfo.setFirstName(userLookUp.getFirstName());
    userDisplayInfo.setMiddleName(userLookUp.getMiddleName());
    userDisplayInfo.setLastName(userLookUp.getLastName());
    return userDisplayInfo;
  }

  private Function<Optional<AccessType>, AccessType> getAccessTypeOrFail(String id) {
    return accessType -> accessType.orElseThrow(() -> ServiceExceptions.notFound("Access type", id));
  }

}
