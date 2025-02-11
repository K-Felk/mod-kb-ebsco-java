package org.folio.repository.holdings.transaction;

import static org.folio.common.FunctionUtils.nothing;
import static org.folio.common.LogUtils.logInsertQueryDebugLevel;
import static org.folio.common.LogUtils.logSelectQueryDebugLevel;
import static org.folio.db.RowSetUtils.mapFirstItem;
import static org.folio.repository.holdings.transaction.TransactionIdTableConstants.getLastTransactionIdByCredentials;
import static org.folio.repository.holdings.transaction.TransactionIdTableConstants.insertTransactionId;
import static org.folio.repository.holdings.transaction.TransactionIdTableConstants.TRANSACTION_ID_COLUMN;
import static org.folio.util.FutureUtils.mapResult;
import static org.folio.util.FutureUtils.mapVertxFuture;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import org.folio.rest.persist.PostgresClient;

@Component
public class TransactionIdRepositoryImpl implements TransactionIdRepository {

  private static final Logger LOG = LogManager.getLogger(TransactionIdRepositoryImpl.class);

  private final Vertx vertx;

  public TransactionIdRepositoryImpl(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public CompletableFuture<Void> save(UUID credentialsId, String transactionId, String tenantId) {
    final Tuple params = Tuple.of(credentialsId, transactionId);
    final String query = insertTransactionId(tenantId, params);
    logInsertQueryDebugLevel(LOG, query, params);
    Promise<RowSet<Row>> promise = Promise.promise();
    pgClient(tenantId).execute(query, params, promise);
    return mapVertxFuture(promise.future()).thenApply(nothing());
  }

  @Override
  public CompletableFuture<String> getLastTransactionId(UUID credentialsId, String tenantId) {
    final Tuple params = Tuple.of(credentialsId);
    final String query = getLastTransactionIdByCredentials(tenantId);
    logSelectQueryDebugLevel(LOG, query, params);
    Promise<RowSet<Row>> promise = Promise.promise();
    pgClient(tenantId).select(query, params, promise);
    return mapResult(promise.future(), this::mapId);
  }

  private String mapId(RowSet<Row> resultSet) {
    return mapFirstItem(resultSet, row -> row.getString(TRANSACTION_ID_COLUMN));
  }

  private PostgresClient pgClient(String tenantId) {
    return PostgresClient.getInstance(vertx, tenantId);
  }
}
