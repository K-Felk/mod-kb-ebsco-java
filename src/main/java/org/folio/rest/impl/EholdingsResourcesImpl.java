package org.folio.rest.impl;

import static org.apache.http.HttpStatus.SC_NOT_FOUND;
import static org.apache.http.protocol.HTTP.CONTENT_TYPE;

import static org.folio.rest.util.ErrorUtil.createError;
import static org.folio.rest.util.ExceptionMappers.error400NotFoundMapper;
import static org.folio.rest.util.ExceptionMappers.error422InputValidationMapper;
import static org.folio.rest.util.RestConstants.JSONAPI;
import static org.folio.rest.util.RestConstants.JSON_API_TYPE;
import static org.folio.rest.util.RestConstants.TAGS_TYPE;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.factory.annotation.Autowired;

import org.folio.holdingsiq.model.CustomerResources;
import org.folio.holdingsiq.model.FilterQuery;
import org.folio.holdingsiq.model.OkapiData;
import org.folio.holdingsiq.model.PackageByIdData;
import org.folio.holdingsiq.model.PackageId;
import org.folio.holdingsiq.model.ResourceId;
import org.folio.holdingsiq.model.ResourcePut;
import org.folio.holdingsiq.model.ResourceSelectedPayload;
import org.folio.holdingsiq.model.Sort;
import org.folio.holdingsiq.model.Title;
import org.folio.holdingsiq.service.PackagesHoldingsIQService;
import org.folio.holdingsiq.service.TitlesHoldingsIQService;
import org.folio.holdingsiq.service.exception.ResourceNotFoundException;
import org.folio.repository.RecordKey;
import org.folio.repository.RecordType;
import org.folio.repository.resources.ResourceRepository;
import org.folio.repository.tag.TagRepository;
import org.folio.rest.aspect.HandleValidationErrors;
import org.folio.rest.converter.resources.ResourceRequestConverter;
import org.folio.rest.exception.InputValidationException;
import org.folio.rest.jaxrs.model.AccessTypeCollectionItem;
import org.folio.rest.jaxrs.model.Resource;
import org.folio.rest.jaxrs.model.ResourcePostDataAttributes;
import org.folio.rest.jaxrs.model.ResourcePostRequest;
import org.folio.rest.jaxrs.model.ResourcePutRequest;
import org.folio.rest.jaxrs.model.ResourceTags;
import org.folio.rest.jaxrs.model.ResourceTagsDataAttributes;
import org.folio.rest.jaxrs.model.ResourceTagsItem;
import org.folio.rest.jaxrs.model.ResourceTagsPutRequest;
import org.folio.rest.jaxrs.model.Tags;
import org.folio.rest.jaxrs.resource.EholdingsResources;
import org.folio.rest.parser.IdParser;
import org.folio.rest.tools.utils.TenantTool;
import org.folio.rest.util.ErrorHandler;
import org.folio.rest.util.template.RMAPITemplateContext;
import org.folio.rest.util.template.RMAPITemplateFactory;
import org.folio.rest.validator.ResourcePostValidator;
import org.folio.rest.validator.ResourcePutBodyValidator;
import org.folio.rest.validator.ResourceTagsPutBodyValidator;
import org.folio.rmapi.result.ObjectsForPostResourceResult;
import org.folio.rmapi.result.ResourceResult;
import org.folio.service.accesstypes.AccessTypeMappingsService;
import org.folio.service.accesstypes.AccessTypesService;
import org.folio.service.loader.RelatedEntitiesLoader;
import org.folio.spring.SpringContextUtil;


public class EholdingsResourcesImpl implements EholdingsResources {

  private static final int MAX_TITLE_COUNT = 100;

  private static final String RESOURCE_NOT_FOUND_MESSAGE = "Resource not found";
  private static final String RESOURCE_CANNOT_BE_DELETED_TITLE = "Resource cannot be deleted";
  private static final String RESOURCE_CANNOT_BE_DELETED_DETAIL = "Resource is not in a custom package";

  @Autowired
  private ResourceRequestConverter converter;
  @Autowired
  private IdParser idParser;
  @Autowired
  private ResourcePostValidator postValidator;
  @Autowired
  private ResourcePutBodyValidator resourcePutBodyValidator;
  @Autowired
  private RMAPITemplateFactory templateFactory;
  @Autowired
  private TagRepository tagRepository;
  @Autowired
  private ResourceRepository resourceRepository;
  @Autowired
  private ResourceTagsPutBodyValidator resourceTagsPutBodyValidator;
  @Autowired
  private AccessTypesService accessTypesService;
  @Autowired
  private AccessTypeMappingsService accessTypeMappingsService;
  @Autowired
  private RelatedEntitiesLoader relatedEntitiesLoader;

  public EholdingsResourcesImpl() {
    SpringContextUtil.autowireDependencies(this, Vertx.currentContext());
  }

  @Override
  @HandleValidationErrors
  public void postEholdingsResources(String contentType, ResourcePostRequest entity, Map<String, String> okapiHeaders,
                                     Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    postValidator.validate(entity);

    ResourcePostDataAttributes attributes = entity.getData().getAttributes();

    long titleId = idParser.parseTitleId(attributes.getTitleId());
    PackageId packageId = idParser.parsePackageId(attributes.getPackageId());

    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction(
        context -> (CompletableFuture<?>) getObjectsForPostResource(titleId, packageId, context.getTitlesService(),
          context.getPackagesService())
          .thenCompose(result -> {
            Title title = result.getTitle();
            postValidator.validateRelatedObjects(result.getPackageData(), title, result.getTitles());
            ResourceSelectedPayload postRequest =
              new ResourceSelectedPayload(true, title.getTitleName(), title.getPubType(), attributes.getUrl());
            ResourceId resourceId = ResourceId.builder()
              .providerIdPart(packageId.getProviderIdPart())
              .packageIdPart(packageId.getPackageIdPart())
              .titleIdPart(titleId)
              .build();
            return context.getResourcesService().postResource(postRequest, resourceId);
          })
          .thenCompose(title -> CompletableFuture.completedFuture(
            new ResourceResult(title, null, null, false)))
      )
      .addErrorMapper(InputValidationException.class, error422InputValidationMapper())
      .executeWithResult(Resource.class);
  }

  @Override
  @HandleValidationErrors
  public void getEholdingsResourcesByResourceId(String resourceId, String include, Map<String, String> okapiHeaders,
                                                Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    ResourceId parsedResourceId = idParser.parseResourceId(resourceId);
    List<String> includedObjects = include != null ? Arrays.asList(include.split(",")) : Collections.emptyList();

    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction(context ->
        context.getResourcesService().retrieveResource(parsedResourceId, includedObjects)
          .thenCompose(result -> loadRelatedEntities(result, okapiHeaders))
      )
      .addErrorMapper(ResourceNotFoundException.class, error404ResourceNotFoundMapper())
      .executeWithResult(Resource.class);
  }

  @Override
  @HandleValidationErrors
  public void putEholdingsResourcesByResourceId(String resourceId, String contentType, ResourcePutRequest entity,
                                                Map<String, String> okapiHeaders,
                                                Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    ResourceId parsedResourceId = idParser.parseResourceId(resourceId);
    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction(context -> fetchAccessType(entity, okapiHeaders)
        .thenCompose(accessType -> processResourceUpdate(entity, parsedResourceId, context)
          .thenCompose(resourceResult -> updateAccessType(resourceId, resourceResult, accessType, okapiHeaders))
        ))
      .addErrorMapper(InputValidationException.class, error422InputValidationMapper())
      .addErrorMapper(ResourceNotFoundException.class, error404ResourceNotFoundMapper())
      .addErrorMapper(NotFoundException.class, error400NotFoundMapper())
      .executeWithResult(Resource.class);
  }

  @Override
  @HandleValidationErrors
  public void deleteEholdingsResourcesByResourceId(String resourceId, Map<String, String> okapiHeaders,
                                                   Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    ResourceId parsedResourceId = idParser.parseResourceId(resourceId);

    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction(context ->
        context.getResourcesService().retrieveResource(parsedResourceId)
          .thenCompose(title -> {
            if (BooleanUtils.isNotTrue(title.getCustomerResourcesList().get(0).getIsPackageCustom())) {
              throw new InputValidationException(RESOURCE_CANNOT_BE_DELETED_TITLE, RESOURCE_CANNOT_BE_DELETED_DETAIL);
            }
            return context.getResourcesService().deleteResource(parsedResourceId);
          })
          .thenCompose(o -> deleteAssignedResources(resourceId, okapiHeaders))
      )
      .execute();
  }

  @Override
  public void putEholdingsResourcesTagsByResourceId(String resourceId, String contentType, ResourceTagsPutRequest entity,
                                                    Map<String, String> okapiHeaders,
                                                    Handler<AsyncResult<Response>> asyncResultHandler,
                                                    Context vertxContext) {
    CompletableFuture.completedFuture(null)
      .thenCompose(o -> {
        ResourceTagsDataAttributes attributes = entity.getData().getAttributes();
        resourceTagsPutBodyValidator.validate(entity, attributes);
        return updateResourceTags(resourceId, entity.getData().getAttributes().getName(), attributes.getTags(),
          new OkapiData(okapiHeaders).getTenant())
          .thenAccept(ob -> asyncResultHandler.handle(
            Future.succeededFuture(PutEholdingsResourcesTagsByResourceIdResponse.respond200WithApplicationVndApiJson(
              convertToResourceTags(attributes)))));
      })
      .exceptionally(e -> {
        new ErrorHandler()
          .addInputValidation422Mapper()
          .handle(asyncResultHandler, e);
        return null;
      });
  }

  private CompletableFuture<AccessTypeCollectionItem> fetchAccessType(ResourcePutRequest entity,
                                                                      Map<String, String> okapiHeaders) {
    String accessTypeId = entity.getData().getAttributes().getAccessTypeId();
    if (accessTypeId == null) {
      return CompletableFuture.completedFuture(null);
    } else {
      return accessTypesService.findById(accessTypeId, okapiHeaders);
    }
  }

  private CompletableFuture<ResourceResult> updateAccessType(String recordId, Title titleResult,
                                                             AccessTypeCollectionItem accessType,
                                                             Map<String, String> okapiHeaders) {
    return updateRecordMapping(accessType, recordId, okapiHeaders)
      .thenApply(a -> {
        ResourceResult resourceResult = new ResourceResult(titleResult, null, null, false);
        resourceResult.setAccessType(accessType);
        return resourceResult;
      });
  }

  private CompletableFuture<Void> updateRecordMapping(AccessTypeCollectionItem accessType, String recordId,
                                                      Map<String, String> okapiHeaders) {
    return accessTypeMappingsService.update(accessType, recordId, RecordType.RESOURCE, okapiHeaders);
  }

  private CompletableFuture<ResourceResult> loadRelatedEntities(ResourceResult result, Map<String, String> okapiHeaders) {
    CustomerResources resource = result.getTitle().getCustomerResourcesList().get(0);
    RecordKey recordKey = RecordKey.builder().recordId(getResourceId(resource)).recordType(RecordType.RESOURCE).build();
    return CompletableFuture.allOf(
      relatedEntitiesLoader.loadAccessType(result, recordKey, okapiHeaders),
      relatedEntitiesLoader.loadTags(result, recordKey, okapiHeaders))
      .thenApply(aVoid -> result);
  }

  private ResourceTags convertToResourceTags(ResourceTagsDataAttributes attributes) {
    return new ResourceTags()
      .withData(new ResourceTagsItem()
        .withType(TAGS_TYPE)
        .withAttributes(attributes))
      .withJsonapi(JSONAPI);
  }

  private CompletableFuture<Void> deleteAssignedResources(String resourceId, Map<String, String> okapiHeaders) {
    CompletableFuture<Void> deleteAccessMapping = updateRecordMapping(null, resourceId, okapiHeaders);
    CompletableFuture<Void> deleteTags = deleteTags(resourceId, TenantTool.tenantId(okapiHeaders));

    return CompletableFuture.allOf(deleteAccessMapping, deleteTags);
  }

  private CompletableFuture<Void> deleteTags(String resourceId, String tenant) {
    return resourceRepository.delete(resourceId, tenant)
      .thenCompose(o -> tagRepository.deleteRecordTags(tenant, resourceId, RecordType.RESOURCE))
      .thenCompose(aBoolean -> CompletableFuture.completedFuture(null));
  }

  private CompletionStage<ObjectsForPostResourceResult> getObjectsForPostResource(Long titleId, PackageId packageId,
                                                                                  TitlesHoldingsIQService titlesService,
                                                                                  PackagesHoldingsIQService packagesService) {
    CompletableFuture<Title> titleFuture = titlesService.retrieveTitle(titleId);
    CompletableFuture<PackageByIdData> packageFuture = packagesService.retrievePackage(packageId);
    return CompletableFuture.allOf(titleFuture, packageFuture)
      .thenCompose(o -> {
        FilterQuery filterByName = FilterQuery.builder()
          .name(titleFuture.join().getTitleName())
          .build();
        return titlesService.retrieveTitles(packageId.getProviderIdPart(), packageId.getPackageIdPart(),
          filterByName, Sort.RELEVANCE, 1, MAX_TITLE_COUNT);
      })
      .thenCompose(titles -> CompletableFuture.completedFuture(
        new ObjectsForPostResourceResult(titleFuture.join(), packageFuture.join(), titles)));
  }

  private CompletableFuture<Void> updateStoredResource(String resourceId, String name, String tenant, Tags tags) {
    if (!tags.getTagList().isEmpty()) {
      return resourceRepository.save(resourceId, name, tenant);
    }
    return resourceRepository.delete(resourceId, tenant);
  }

  private CompletableFuture<Void> updateResourceTags(String resourceId, String name, Tags tags, String tenant) {
    if (Objects.isNull(tags)) {
      return CompletableFuture.completedFuture(null);
    } else {
      return updateStoredResource(resourceId, name, tenant, tags)
        .thenCompose(resourceUpdate -> tagRepository
          .updateRecordTags(tenant, resourceId, RecordType.RESOURCE, tags.getTagList()))
        .thenApply(updated -> null);
    }
  }

  private String getResourceId(CustomerResources resource) {
    return resource.getVendorId() + "-" + resource.getPackageId() + "-" + resource.getTitleId();
  }

  private CompletableFuture<Title> processResourceUpdate(ResourcePutRequest entity, ResourceId parsedResourceId,
                                                         RMAPITemplateContext context) {
    return context.getResourcesService().retrieveResource(parsedResourceId)
      .thenCompose(title -> {
        ResourcePut resourcePutBody;
        boolean isTitleCustom = title.getIsTitleCustom();
        resourcePutBodyValidator.validate(entity, isTitleCustom);
        if (isTitleCustom) {
          resourcePutBody = converter.convertToRMAPICustomResourcePutRequest(entity, title);
        } else {
          resourcePutBody = converter.convertToRMAPIResourcePutRequest(entity, title);
        }
        return context.getResourcesService().updateResource(parsedResourceId, resourcePutBody);
      })
      .thenCompose(o -> context.getResourcesService().retrieveResource(parsedResourceId));
  }

  private Function<ResourceNotFoundException, Response> error404ResourceNotFoundMapper() {
    return exception ->
      Response.status(SC_NOT_FOUND)
        .header(CONTENT_TYPE, JSON_API_TYPE)
        .entity(createError(RESOURCE_NOT_FOUND_MESSAGE))
        .build();
  }
}
