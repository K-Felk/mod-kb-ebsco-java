package org.folio.rmapi.result;

import java.util.List;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class TitleCollectionResult {

  private List<TitleResult> titleResults;
  private Integer totalResults;
}
