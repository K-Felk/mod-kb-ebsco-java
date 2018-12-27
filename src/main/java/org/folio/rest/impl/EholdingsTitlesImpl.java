package org.folio.rest.impl;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.ws.rs.core.Response;

import org.folio.rest.annotations.Validate;
import org.folio.rest.aspect.HandleValidationErrors;
import org.folio.rest.converter.TitlesConverter;
import org.folio.rest.jaxrs.model.Title;
import org.folio.rest.jaxrs.model.TitleCollection;
import org.folio.rest.jaxrs.model.TitlePostRequest;
import org.folio.rest.jaxrs.resource.EholdingsTitles;
import org.folio.rest.model.FilterQuery;
import org.folio.rest.model.PackageId;
import org.folio.rest.model.Sort;
import org.folio.rest.parser.IdParser;
import org.folio.rest.util.ErrorUtil;
import org.folio.rest.util.template.RMAPITemplateFactory;
import org.folio.rest.validator.TitleParametersValidator;
import org.folio.rest.validator.TitlesPostBodyValidator;
import org.folio.rmapi.exception.RMAPIResourceNotFoundException;
import org.folio.rmapi.model.TitlePost;
import org.folio.rmapi.result.TitleResult;
import org.folio.spring.SpringContextUtil;
import org.springframework.beans.factory.annotation.Autowired;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;

public class EholdingsTitlesImpl implements EholdingsTitles {
  private static final String GET_TITLE_NOT_FOUND_MESSAGE = "Title not found";
  private static final String INCLUDE_RESOURCES_VALUE = "resources";

  @Autowired
  private TitlesConverter converter;
  @Autowired
  private TitleParametersValidator parametersValidator;
  @Autowired
  private IdParser idParser;
  @Autowired
  private TitlesPostBodyValidator titlesPostBodyValidator;
  @Autowired
  private RMAPITemplateFactory templateFactory;

  public EholdingsTitlesImpl() {
    SpringContextUtil.autowireDependencies(this, Vertx.currentContext());
  }

  @Override
  @Validate
  @HandleValidationErrors
  public void getEholdingsTitles(String filterSelected, String filterType, String filterName, String filterIsxn, String filterSubject,
                                 String filterPublisher, String sort, int page, int count, Map<String, String> okapiHeaders,
                                 Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    FilterQuery fq = FilterQuery.builder()
      .selected(filterSelected).type(filterType)
      .name(filterName).isxn(filterIsxn).subject(filterSubject)
      .publisher(filterPublisher).build();

    parametersValidator.validate(fq, sort);

    Sort nameSort = Sort.valueOf(sort.toUpperCase());

    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction((rmapiService, okapiData) ->
        rmapiService.retrieveTitles(fq, nameSort, page, count)
      )
      .executeWithResult(TitleCollection.class);
  }

  @Override
  @HandleValidationErrors
  public void postEholdingsTitles(String contentType, TitlePostRequest entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    titlesPostBodyValidator.validate(entity);

    TitlePost titlePost = converter.convertToPost(entity);
    PackageId packageId = idParser.parsePackageId(entity.getIncluded().get(0).getAttributes().getPackageId());

    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction((rmapiService, okapiData) ->
        rmapiService.postTitle(titlePost, packageId)
          .thenCompose(title -> CompletableFuture.completedFuture(new TitleResult(title, false)))
      )
      .executeWithResult(Title.class);
  }

  @Override
  @HandleValidationErrors
  public void getEholdingsTitlesByTitleId(String titleId, String include, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    long titleIdLong = idParser.parseTitleId(titleId);
    boolean includeResource = INCLUDE_RESOURCES_VALUE.equalsIgnoreCase(include);

    templateFactory.createTemplate(okapiHeaders, asyncResultHandler)
      .requestAction((rmapiService, okapiData) ->
        rmapiService.retrieveTitle(titleIdLong)
          .thenCompose(title -> CompletableFuture.completedFuture(new TitleResult(title, includeResource)))
      )
      .addErrorMapper(RMAPIResourceNotFoundException.class, exception ->
        GetEholdingsTitlesByTitleIdResponse
          .respond404WithApplicationVndApiJson(ErrorUtil.createError(GET_TITLE_NOT_FOUND_MESSAGE))
      )
      .executeWithResult(Title.class);
  }
}
