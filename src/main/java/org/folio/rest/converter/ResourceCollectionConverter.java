package org.folio.rest.converter;

import java.util.List;
import java.util.stream.Collectors;

import org.folio.rest.jaxrs.model.MetaTotalResults;
import org.folio.rest.jaxrs.model.ResourceCollection;
import org.folio.rest.jaxrs.model.ResourceCollectionItem;
import org.folio.rest.util.RestConstants;
import org.folio.rmapi.model.Titles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class ResourceCollectionConverter implements Converter<Titles, ResourceCollection> {

  @Autowired
  ResourcesConverter converter;
  @Override
  public ResourceCollection convert(Titles titles) {
    List<ResourceCollectionItem> titleList = titles.getTitleList().stream()
      .map(converter::convertResource)
      .collect(Collectors.toList());
    return new ResourceCollection()
      .withJsonapi(RestConstants.JSONAPI)
      .withMeta(new MetaTotalResults().withTotalResults(titles.getTotalResults()))
      .withData(titleList);

  }
}
