package org.folio.tag.repository.titles;

import static org.folio.tag.repository.TagTableConstants.RECORD_ID_COLUMN;
import static org.folio.tag.repository.TagTableConstants.RECORD_TYPE_COLUMN;
import static org.folio.tag.repository.TagTableConstants.TAG_COLUMN;

public class TitlesTableConstants {

  public static final String TITLES_TABLE_NAME = "titles";
  public static final String ID_COLUMN = "id";
  public static final String NAME_COLUMN = "name";
  public static final String TITLE_FIELD_LIST = String.format("%s, %s", ID_COLUMN, NAME_COLUMN);
  public static final String INSERT_OR_UPDATE_TITLE_STATEMENT = "INSERT INTO %s(" + TITLE_FIELD_LIST + ") VALUES (?, ?) "
    + "ON CONFLICT (" + ID_COLUMN + ") DO UPDATE " + "SET " + NAME_COLUMN + " = ?;";
  public static final String DELETE_TITLE_STATEMENT = "DELETE FROM %s " + "WHERE " + ID_COLUMN + "=?";

  public static final String COUNT_TITLES_BY_RESOURCE_TAGS =
    "SELECT COUNT(DISTINCT (regexp_split_to_array(" + RECORD_ID_COLUMN + ", '-'))[3]) AS count " +
    "FROM %s WHERE " + TAG_COLUMN + " IN (%s) AND " + RECORD_TYPE_COLUMN + "='resource'";

  public static final String SELECT_TITLES_BY_RESOURCE_TAGS =
    "SELECT DISTINCT (regexp_split_to_array(resources.id, '-'))[3] as id, resources.name as name, holdings.jsonb as holding " +
    "FROM %s as resources " +
    "LEFT JOIN %s as tags ON " +
    "tags.record_id = resources.id " +
    "AND tags.record_type = 'resource' " +
    "LEFT JOIN %s as holdings ON " +
    "holdings.id = resources.id " +
    "WHERE tags.tag IN (%s) " +
    "ORDER BY resources.name " +
    "OFFSET ? " +
    "LIMIT ? ";

  private TitlesTableConstants() {}

}

