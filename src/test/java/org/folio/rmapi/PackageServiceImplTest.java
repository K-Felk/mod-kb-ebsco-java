package org.folio.rmapi;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

import static org.folio.test.util.TestUtil.STUB_TENANT;
import static org.folio.test.util.TestUtil.mockGet;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;

import com.github.tomakehurst.wiremock.common.Slf4jNotifier;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.matching.RegexPattern;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import io.vertx.core.Vertx;
import org.junit.Rule;
import org.junit.Test;

import org.folio.cache.VertxCache;
import org.folio.holdingsiq.model.Configuration;
import org.folio.holdingsiq.model.PackageId;

public class PackageServiceImplTest {

  protected static final String STUB_CUSTOMER_ID = "TEST_CUSTOMER_ID";
  private static final int STUB_PACKAGE_ID = 3964;
  private static final int STUB_VENDOR_ID = 111111;
  private static final String CUSTOM_PACKAGE_STUB_FILE = "responses/rmapi/packages/get-custom-package-by-id-response.json";

  @Rule
  public WireMockRule userMockServer = new WireMockRule(
    WireMockConfiguration.wireMockConfig()
      .dynamicPort()
      .notifier(new Slf4jNotifier(true)));

  @Test
  public void shouldReturnCachedPackage() throws IOException, URISyntaxException {
    RegexPattern getPackagePattern = new RegexPattern("/rm/rmaccounts/" + STUB_CUSTOMER_ID + "/vendors/" + STUB_VENDOR_ID + "/packages/" + STUB_PACKAGE_ID);

    Configuration configuration = Configuration.builder()
      .url("http://127.0.0.1:" + userMockServer.port())
      .customerId(STUB_CUSTOMER_ID)
      .apiKey("API KEY")
      .build();
    PackageServiceImpl service = new PackageServiceImpl(configuration, Vertx.vertx(), STUB_TENANT, null, null,
      new VertxCache<>(Vertx.vertx(), 60, "packageCache"), null);

    mockGet(getPackagePattern, CUSTOM_PACKAGE_STUB_FILE);

    PackageId packageId = PackageId.builder()
      .packageIdPart(STUB_PACKAGE_ID)
      .providerIdPart(STUB_VENDOR_ID)
      .build();
    service.retrievePackage(packageId, Collections.emptyList(), true).join();
    service.retrievePackage(packageId, Collections.emptyList(), true).join();

    verify(1, getRequestedFor(new UrlPattern(getPackagePattern, true)));
  }
}
