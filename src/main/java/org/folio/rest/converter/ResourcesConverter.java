package org.folio.rest.converter;

import static java.util.stream.Collectors.toList;
import static org.folio.rest.util.RestConstants.PACKAGES_TYPE;
import static org.folio.rest.util.RestConstants.PROVIDERS_TYPE;
import static org.folio.rest.util.RestConstants.TITLES_TYPE;

import java.util.ArrayList;

import org.folio.rest.jaxrs.model.HasOneRelationship;
import org.folio.rest.jaxrs.model.MetaDataIncluded;
import org.folio.rest.jaxrs.model.RelationshipData;
import org.folio.rest.jaxrs.model.Resource;
import org.folio.rest.jaxrs.model.ResourceCollectionItem;
import org.folio.rest.jaxrs.model.ResourceDataAttributes;
import org.folio.rest.jaxrs.model.ResourceRelationships;
import org.folio.rest.util.RestConstants;
import org.folio.rmapi.model.PackageByIdData;
import org.folio.rmapi.model.Title;
import org.folio.rmapi.model.VendorById;

import java.util.List;

public class ResourcesConverter {
  private CommonAttributesConverter commonConverter;

  private TitleConverter titleConverter;
  private VendorConverter vendorConverter;
  private PackagesConverter packagesConverter;
  public ResourcesConverter() {
    this.commonConverter = new CommonAttributesConverter();
    this.vendorConverter = new VendorConverter();
    this.packagesConverter = new PackagesConverter();
    this.titleConverter = new TitleConverter(commonConverter,this);
  }

  public ResourcesConverter(CommonAttributesConverter commonConverter, TitleConverter titleConverter, VendorConverter vendorConverter, PackagesConverter packagesConverter) {
    this.commonConverter = commonConverter;
    this.titleConverter = titleConverter;
    this.vendorConverter = vendorConverter;
    this.packagesConverter = packagesConverter;
  }

  public List<Resource> convertFromRMAPIResource(Title title, VendorById vendor, PackageByIdData packageData, boolean includeTitle) {
    return title.getCustomerResourcesList().stream().map(resource -> {
      Resource resultResource = new org.folio.rest.jaxrs.model.Resource()
        .withData(new ResourceCollectionItem()
          .withId(String.valueOf(resource.getVendorId() + "-" + resource.getPackageId() + "-" + resource.getTitleId()))
          .withType(ResourceCollectionItem.Type.RESOURCES)
          .withAttributes(new ResourceDataAttributes()
            .withDescription(title.getDescription())
            .withEdition(title.getEdition())
            .withIsPeerReviewed(title.getIsPeerReviewed())
            .withIsTitleCustom(title.getIsTitleCustom())
            .withPublisherName(title.getPublisherName())
            .withTitleId(title.getTitleId())
            .withContributors(commonConverter.convertContributors(title.getContributorsList()))
            .withIdentifiers(commonConverter.convertIdentifiers(title.getIdentifiersList()))
            .withName(title.getTitleName())
            .withPublicationType(CommonAttributesConverter.publicationTypes.get(title.getPubType().toLowerCase()))
            .withSubjects(commonConverter.convertSubjects(title.getSubjectsList()))
            .withCoverageStatement(resource.getCoverageStatement())
            .withCustomEmbargoPeriod(commonConverter.convertEmbargo(resource.getCustomEmbargoPeriod()))
            .withIsPackageCustom(resource.getIsPackageCustom())
            .withIsSelected(resource.getIsSelected())
            .withIsTokenNeeded(resource.getIsTokenNeeded())
            .withLocationId(resource.getLocationId())
            .withManagedEmbargoPeriod(commonConverter.convertEmbargo(resource.getManagedEmbargoPeriod()))
            .withPackageId(String.valueOf(resource.getVendorId() + "-" + resource.getPackageId()))
            .withPackageName(resource.getPackageName())
            .withUrl(resource.getUrl())
            .withProviderId(resource.getVendorId())
            .withProviderName(resource.getVendorName())
            .withVisibilityData(commonConverter.convertVisibilityData(resource.getVisibilityData()))
            .withManagedCoverages(commonConverter.convertCoverages(resource.getManagedCoverageList()))
            .withCustomCoverages(commonConverter.convertCoverages(resource.getCustomCoverageList()))
            .withProxy(commonConverter.convertProxy(resource.getProxy())))
          .withRelationships(createEmptyRelationship())
        )
        .withIncluded(null)
        .withJsonapi(RestConstants.JSONAPI);
      resultResource.setIncluded(new ArrayList<>());
      if (includeTitle) {
        resultResource.getIncluded().add(titleConverter.convertFromRMAPITitle(title, null).getData());
        resultResource.getData()
          .getRelationships()
          .withTitle(new HasOneRelationship()
            .withData(new RelationshipData()
              .withId(String.valueOf(title.getTitleId()))
              .withType(TITLES_TYPE)));
      }
      if(vendor != null){
        resultResource.getIncluded().add(vendorConverter.convertToProvider(vendor).getData());
        resultResource.getData()
          .getRelationships()
          .withProvider(new HasOneRelationship()
            .withData(new RelationshipData()
              .withId(String.valueOf(vendor.getVendorId()))
              .withType(PROVIDERS_TYPE)));
      }
      if(packageData != null){
        resultResource.getIncluded().add(packagesConverter.convert(packageData).getData());
        resultResource.getData()
          .getRelationships()
          .withPackage(new HasOneRelationship()
            .withData(new RelationshipData()
              .withId(String.valueOf(packageData.getVendorId() + "-" + packageData.getPackageId()))
              .withType(PACKAGES_TYPE)));
      }
      return resultResource;
    }).collect(toList());
  }

  private ResourceRelationships createEmptyRelationship() {
    return new ResourceRelationships()
      .withProvider(new HasOneRelationship()
        .withMeta(
          new MetaDataIncluded()
            .withIncluded(false)))
      .withPackage(new HasOneRelationship().withMeta(
        new MetaDataIncluded()
          .withIncluded(false)))
      .withTitle(new HasOneRelationship().withMeta(
        new MetaDataIncluded()
          .withIncluded(false)));
  }
}
