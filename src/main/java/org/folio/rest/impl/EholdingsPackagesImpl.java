package org.folio.rest.impl;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.validation.ValidationException;
import javax.ws.rs.core.Response;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.convert.converter.Converter;

import org.folio.cache.VertxCache;
import org.folio.config.cache.VendorIdCacheKey;
import org.folio.holdingsiq.model.CustomerResources;
import org.folio.holdingsiq.model.FilterQuery;
import org.folio.holdingsiq.model.PackageByIdData;
import org.folio.holdingsiq.model.PackageId;
import org.folio.holdingsiq.model.PackagePost;
import org.folio.holdingsiq.model.PackagePut;
import org.folio.holdingsiq.model.Packages;
import org.folio.holdingsiq.model.Sort;
import org.folio.holdingsiq.model.Titles;
import org.folio.holdingsiq.service.exception.ResourceNotFoundException;
import org.folio.holdingsiq.service.exception.ServiceResponseException;
import org.folio.holdingsiq.service.validator.PackageParametersValidator;
import org.folio.holdingsiq.service.validator.TitleParametersValidator;
import org.folio.repository.RecordType;
import org.folio.repository.packages.DbPackage;
import org.folio.repository.packages.PackageRepository;
import org.folio.repository.tag.Tag;
import org.folio.repository.tag.TagRepository;
import org.folio.rest.annotations.Validate;
import org.folio.rest.aspect.HandleValidationErrors;
import org.folio.rest.converter.packages.PackageRequestConverter;
import org.folio.rest.exception.InputValidationException;
import org.folio.rest.jaxrs.model.Package;
import org.folio.rest.jaxrs.model.PackageCollection;
import org.folio.rest.jaxrs.model.PackageDataAttributes;
import org.folio.rest.jaxrs.model.PackagePostRequest;
import org.folio.rest.jaxrs.model.PackagePutRequest;
import org.folio.rest.jaxrs.model.ResourceCollection;
import org.folio.rest.jaxrs.model.Tags;
import org.folio.rest.jaxrs.resource.EholdingsPackages;
import org.folio.rest.parser.IdParser;
import org.folio.rest.util.ErrorUtil;
import org.folio.rest.util.RestConstants;
import org.folio.rest.util.template.RMAPITemplate;
import org.folio.rest.util.template.RMAPITemplateContext;
import org.folio.rest.util.template.RMAPITemplateFactory;
import org.folio.rest.validator.CustomPackagePutBodyValidator;
import org.folio.rest.validator.PackagePutBodyValidator;
import org.folio.rest.validator.PackagesPostBodyValidator;
import org.folio.rmapi.result.PackageResult;
import org.folio.rmapi.result.TitleCollectionResult;
import org.folio.rmapi.result.TitleResult;
import org.folio.spring.SpringContextUtil;

public class EholdingsPackagesImpl implements EholdingsPackages {

  private static final String PACKAGE_NOT_FOUND_MESSAGE = "Package not found";

  private static final String INVALID_PACKAGE_TITLE = "Package cannot be deleted";
  private static final String INVALID_PACKAGE_DETAILS = "Invalid package";

  @Autowired
  private PackageRequestConverter converter;
  @Autowired
  private Converter<PackagePostRequest, PackagePost> packagePostRequestConverter;
  @Autowired
  private PackageParametersValidator packageParametersValidator;
  @Autowired
  private PackagePutBodyValidator packagePutBodyValidator;
  @Autowired
  private CustomPackagePutBodyValidator customPackagePutBodyValidator;
  @Autowired
  private PackagesPostBodyValidator packagesPostBodyValidator;
  @Autowired
  private TitleParametersValidator titleParametersValidator;
  @Autowired
  private IdParser idParser;
  @Autowired
  private RMAPITemplateFactory templateFactory;
  @Autowired
  @Qualifier("vendorIdCache")
  private VertxCache<VendorIdCacheKey, Long> vendorIdCache;
  @Autowired
  private TagRepository tagRepository;
  @Autowired
  private Converter<List<Tag>, Tags> tagsConverter;
  @Autowired
  private PackageRepository packageRepository;
  @Autowired
  private Converter<Titles, TitleCollectionResult> titleCollectionConverter;

  public EholdingsPackagesImpl() {
    SpringContextUtil.autowireDependencies(this, Vertx.currentContext());
  }

  @Override
  @Validate
  @HandleValidationErrors
  public void getEholdingsPackages(String filterCustom, String q, String filterSelected,
                                   String filterType, String filterTags, String sort, int page, int count, Map<String, String> okapiHeaders,
                                   Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    RMAPITemplate template = templateFactory.createTemplate(okapiHeaders, asyncResultHandler);
    if(isTagOnlySearch(q, filterTags)){
      List<String> tags = Arrays.asList(filterTags.split("\\s*,\\s*"));
      template.requestAction(context -> getPackagesByTags(tags, page, count, context));
    }
    else{
      if(Objects.nonNull(filterCustom) && !Boolean.parseBoolean(filterCustom)){
        throw new ValidationException("Invalid Query Parameter for filter[custom]");
      }
      String selected = RestConstants.FILTER_SELECTED_MAPPING.getOrDefault(filterSelected, filterSelected);
      packageParametersValidator.validate(selected, filterType, sort, q);

      boolean isFilterCustom = Boolean.parseBoolean(filterCustom);
      Sort nameSort = Sort.valueOf(sort.toUpperCase());

      template
        .requestAction(context -> {
          if (isFilterCustom) {
            return getVendorId(context)
              .thenCompose(vendorId ->
                context.getPackagesService().retrievePackages(selected, filterType, vendorId, q, page, count, nameSort));
          } else {
            return context.getPackagesService().retrievePackages(selected, filterType, null, q, page, count, nameSort);
          }
        });
    }

    template
      .addErrorMapper(ServiceResponseException.class,
        exception ->
          GetEholdingsPackagesResponse.respond400WithApplicationVndApiJson(
            ErrorUtil.createErrorFromRMAPIResponse(exception)))
      .executeWithResult(PackageCollection.class);
  }

  @Override
  @HandleValidationErrors
  public void postEholdingsPackages(String contentType, PackagePostRequest entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    packagesPostBodyValidator.validate(entity);

    PackagePost packagePost = packagePostRequestConverter.convert(entity);
    final Tags tags = entity.getData().getAttributes().getTags();

    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction(context ->
        getVendorId(context)
          .thenCompose(id -> context.getPackagesService().postPackage(packagePost, id))
          .thenCompose(packageById -> updateTags(packageById, context.getOkapiData().getTenant(), tags))
      )
      .addErrorMapper(ServiceResponseException.class,
        exception ->
          PostEholdingsPackagesResponse.respond400WithApplicationVndApiJson(
            ErrorUtil.createErrorFromRMAPIResponse(exception)))
      .executeWithResult(Package.class);
  }

  @Override
  @HandleValidationErrors
  public void getEholdingsPackagesByPackageId(String packageId, String include, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    PackageId parsedPackageId = idParser.parsePackageId(packageId);
    List<String> includedObjects = include != null ? Arrays.asList(include.split(",")) : Collections.emptyList();

    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction((context ->
        context.getPackagesService().retrievePackage(parsedPackageId, includedObjects)
          .thenCompose(result ->
            loadTags(result, context.getOkapiData().getTenant())
          )
      ))
      .executeWithResult(Package.class);
  }

  @Override
  @HandleValidationErrors
  public void putEholdingsPackagesByPackageId(String packageId, String contentType, PackagePutRequest entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    PackageId parsedPackageId = idParser.parsePackageId(packageId);
    final Tags tags = entity.getData().getAttributes().getTags();
    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction(context -> processUpdateRequest(entity, parsedPackageId, context)
        .thenCompose(o -> {
          CompletableFuture<PackageByIdData> future = context.getPackagesService().retrievePackage(parsedPackageId);
          return handleDeletedPackage(future, parsedPackageId, context.getOkapiData().getTenant());
        })
        .thenCompose(packageById -> updateTags(packageById, context.getOkapiData().getTenant(), tags))
      )
      .addErrorMapper(InputValidationException.class, exception ->
        EholdingsPackages.PutEholdingsPackagesByPackageIdResponse.respond422WithApplicationVndApiJson(
          ErrorUtil.createError(exception.getMessage(), exception.getMessageDetail())))
      .executeWithResult(Package.class);
  }

  @Override
  @HandleValidationErrors
  public void deleteEholdingsPackagesByPackageId(String packageId, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    PackageId parsedPackageId = idParser.parsePackageId(packageId);
    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction(context ->
        context.getPackagesService().retrievePackage(parsedPackageId)
          .thenCompose(packageData -> {
            if (!packageData.getIsCustom()) {
              throw new InputValidationException(INVALID_PACKAGE_TITLE, INVALID_PACKAGE_DETAILS);
            }
            return context.getPackagesService().deletePackage(parsedPackageId).thenCompose(aVoid -> deleteTags(parsedPackageId, context.getOkapiData().getTenant()));
          }))
      .execute();
  }

  @Override
  @Validate
  @HandleValidationErrors
  public void getEholdingsPackagesResourcesByPackageId(String packageId, String sort, String filterSelected, String filterType, String filterName, String filterIsxn, String filterSubject, String filterPublisher, int page, int count, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    PackageId parsedPackageId = idParser.parsePackageId(packageId);

    FilterQuery fq = FilterQuery.builder()
      .selected(RestConstants.FILTER_SELECTED_MAPPING.get(filterSelected))
      .type(filterType)
      .name(filterName).isxn(filterIsxn).subject(filterSubject)
      .publisher(filterPublisher).build();

    titleParametersValidator.validate(fq, sort, true);

    Sort nameSort = Sort.valueOf(sort.toUpperCase());

    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction(context ->
        context.getTitlesService().retrieveTitles(parsedPackageId.getProviderIdPart(), parsedPackageId.getPackageIdPart(),
              fq, nameSort, page, count)
          .thenApply(titles -> titleCollectionConverter.convert(titles))
          .thenCompose(loadResourceTags(context))
      )
      .addErrorMapper(ResourceNotFoundException.class, exception ->
        GetEholdingsPackagesResourcesByPackageIdResponse.respond404WithApplicationVndApiJson(
          ErrorUtil.createError(PACKAGE_NOT_FOUND_MESSAGE)))
      .executeWithResult(ResourceCollection.class);
  }

  private Function<TitleCollectionResult, CompletionStage<TitleCollectionResult>> loadResourceTags(RMAPITemplateContext context) {
    return titleCollection -> {
      Map<String, TitleResult> resourceIdToTitle = mapResourceIdToTitleResult(titleCollection);

      return tagRepository.findPerRecord(context.getOkapiData().getTenant(),
                new ArrayList<>(resourceIdToTitle.keySet()),
                RecordType.RESOURCE)
            .thenApply(tagMap -> {
              populateResourceTags(resourceIdToTitle, tagMap);
              return titleCollection;
            });
    };
  }

  private void populateResourceTags(Map<String, TitleResult> resourceIdToTitle, Map<String, List<Tag>> tagMap) {
    tagMap.forEach((id, tags) -> {
      TitleResult titleResult = resourceIdToTitle.get(id);
      titleResult.setResourceTagList(tags);
    });
  }

  private Map<String, TitleResult> mapResourceIdToTitleResult(TitleCollectionResult tc) {
    return tc.getTitleResults().stream().collect(toMap(this::getResourceId, Function.identity()));
  }

  private String getResourceId(TitleResult titleResult) {
    CustomerResources resource = titleResult.getTitle().getCustomerResourcesList().get(0);
    return resource.getVendorId() + "-" + resource.getPackageId() + "-" + resource.getTitleId();
  }

  private CompletableFuture<Packages> getPackagesByTags(List<String> tags, int page, int count, RMAPITemplateContext context) {
    MutableObject<Integer> totalResults = new MutableObject<>();
    String tenant = context.getOkapiData().getTenant();
    return tagRepository
      .countRecordsByTags(tags, tenant, RecordType.PACKAGE)
      .thenCompose(packageCount -> {
        totalResults.setValue(packageCount);
        return packageRepository.getPackagesByTagName(tags, page, count, tenant);
      })
      .thenCompose(dbPackages ->
        context.getPackagesService().retrievePackages(getPackageIds(dbPackages)))
      .thenApply(packages ->
        packages.toBuilder()
          .totalResults(totalResults.getValue())
          .build()
      );
  }

  private CompletableFuture<Long> getVendorId(RMAPITemplateContext context) {
    VendorIdCacheKey cacheKey = VendorIdCacheKey.builder()
      .tenant(context.getOkapiData().getTenant())
      .rmapiConfiguration(context.getConfiguration())
      .build();
    Long cachedId = vendorIdCache.getValue(cacheKey);
    if(cachedId != null) {
      return completedFuture(cachedId);
    }
    else{
      return context.getProvidersService().getVendorId()
        .thenCompose(id -> {
          vendorIdCache.putValue(cacheKey, id);
          return completedFuture(id);
        });
    }
  }

  private CompletableFuture<PackageResult> loadTags(PackageResult result, String tenant) {
    PackageByIdData packageData = result.getPackageData();
    String packageId = packageData.getVendorId() + "-" + packageData.getPackageId();
    return tagRepository.findByRecord(tenant, packageId, RecordType.PACKAGE)
      .thenCompose(tags -> {
        result.setTags(tagsConverter.convert(tags));
        return completedFuture(result);
      });
  }

  private CompletableFuture<Void> deleteTags(PackageId packageId, String tenant) {
    return
    packageRepository.deletePackage(packageId, tenant)
    .thenCompose(o -> tagRepository.deleteRecordTags(tenant, packageId.getProviderIdPart() + "-" + packageId.getPackageIdPart(), RecordType.PACKAGE))
      .thenCompose(aBoolean ->  completedFuture(null));
  }

  private CompletableFuture<PackageResult> updateTags(PackageByIdData packageData, String tenant, Tags tags) {
    if (tags == null) {
      return completedFuture(new PackageResult(packageData, null, null));
    } else {
      return
        updateStoredPackage(packageData, tags, tenant)
          .thenCompose(o -> tagRepository.updateRecordTags(
            tenant, packageData.getVendorId() + "-" + packageData.getPackageId(), RecordType.PACKAGE, tags.getTagList()))
          .thenCompose(updated -> {
            PackageResult result = new PackageResult(packageData, null, null);
            result.setTags(new Tags().withTagList(tags.getTagList()));
            return completedFuture(result);
          });
    }
  }

  private CompletableFuture<Void> updateStoredPackage(PackageByIdData packageData, Tags tags, String tenant) {
    if(!tags.getTagList().isEmpty()){
      return packageRepository.savePackage(packageData, tenant);
    }
    PackageId packageId = PackageId.builder()
      .providerIdPart(packageData.getVendorId())
      .packageIdPart(packageData.getPackageId())
      .build();
    return packageRepository.deletePackage(packageId, tenant);
  }

  private CompletableFuture<Void> processUpdateRequest(PackagePutRequest entity, PackageId parsedPackageId, RMAPITemplateContext context) {
    if(!isPackageUpdateable(entity)){
      //proceed to next stage without updating
      return completedFuture(null);
    }
    PackagePut packagePutBody;
    if (entity.getData().getAttributes().getIsCustom()) {
      customPackagePutBodyValidator.validate(entity);
      packagePutBody = converter.convertToRMAPICustomPackagePutRequest(entity);
    } else {
      packagePutBodyValidator.validate(entity);
      packagePutBody = converter.convertToRMAPIPackagePutRequest(entity);
    }
    return context.getPackagesService().updatePackage(parsedPackageId, packagePutBody);
  }

  private boolean isPackageUpdateable(PackagePutRequest entity) {
    PackageDataAttributes packageData = entity.getData().getAttributes();
    if (!Objects.isNull(packageData.getIsCustom()) && !packageData.getIsCustom() &&
        !Objects.isNull(packageData.getIsSelected()) && !packageData.getIsSelected()) {
      try {
        packagePutBodyValidator.validate(entity);
      }
      catch (InputValidationException ex){
        return false;
      }
    }
    return true;
  }

  /**
   * Delete local package and tags if package was deleted on update (normally this can only happen in case of custom package),
   * @return future with initial result, or exceptionally completed future if deletion of tags failed
   */
  private CompletableFuture<PackageByIdData> handleDeletedPackage(CompletableFuture<PackageByIdData> future, PackageId packageId, String tenant) {
    CompletableFuture<Void> deleteTagsFuture = new CompletableFuture<>();
    return future.whenComplete((packageById, e) ->
      {
        if (e instanceof ResourceNotFoundException) {
          deleteTags(packageId, tenant)
            .thenAccept(o -> deleteTagsFuture.complete(null));
        } else {
          deleteTagsFuture.complete(null);
        }
      })
      .thenCombine(deleteTagsFuture, (o, aBoolean) -> future.join());
  }

  private boolean isTagOnlySearch(String q, String filterTags) {
    return Strings.isEmpty(q) && !Strings.isEmpty(filterTags);
  }

  private List<PackageId> getPackageIds(List<DbPackage> packageIds) {
    return packageIds.stream().map(DbPackage::getId).collect(Collectors.toList());
  }
}
