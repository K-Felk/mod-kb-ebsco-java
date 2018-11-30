package org.folio.rest.validator;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.folio.rest.exception.InputValidationException;

public class ValidatorUtil {

  private static final String INVALID_FIELD_FORMAT = "Invalid %s";
  private static final String MUST_BE_FALSE_FORMAT = "%s must be false";
  private static final String MUST_BE_NULL_FORMAT = "%s must be null or not specified";
  private static final String MUST_BE_EMPTY_FORMAT = "%s must be empty";
  private static final String MUST_BE_SHORTER_THAN_N_CHARACTERS = "%s is too long (maximum is %s characters)";
  private static final String MUST_BE_VALID_DATE = "%s has invalid format. Should be YYYY-MM-DD";
  private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final String INVALID_DATES_ORDER = "Begin Coverage should be smaller than End Coverage";

  private ValidatorUtil() {}

  public static void checkIsNotEmpty(String paramName, String value) {
    if (StringUtils.isEmpty(value)) {
      throw new InputValidationException(
        String.format(INVALID_FIELD_FORMAT, paramName),
        String.format(MUST_BE_EMPTY_FORMAT, paramName));
    }
  }

  public static void checkIsEmpty(String paramName, String value) {
    if (!StringUtils.isEmpty(value)) {
      throw new InputValidationException(
        String.format(INVALID_FIELD_FORMAT, paramName),
        String.format(MUST_BE_EMPTY_FORMAT, paramName));
    }
  }

  public static void checkFalseOrNull(String paramName, Boolean value) {
    if (Objects.nonNull(value) && value) {
      throw new InputValidationException(
        String.format(INVALID_FIELD_FORMAT, paramName),
        String.format(MUST_BE_FALSE_FORMAT, paramName));
    }
  }

  public static void checkIsNotNull(String paramName, Object value) {
    if (Objects.isNull(value)) {
      throw new InputValidationException(
        String.format(INVALID_FIELD_FORMAT, paramName),
        String.format(MUST_BE_NULL_FORMAT, paramName));
    }
  }

  public static void checkIsNull(String paramName, Object value) {
    if (Objects.nonNull(value)) {
      throw new InputValidationException(
        String.format(INVALID_FIELD_FORMAT, paramName),
        String.format(MUST_BE_NULL_FORMAT, paramName));
    }
  }

  public static void checkDateValid(String paramName, String date) {
    if (!StringUtils.isEmpty(date) && !isDateValid(date)) {
      throw new InputValidationException(
        String.format(INVALID_FIELD_FORMAT, paramName),
        String.format(MUST_BE_VALID_DATE, paramName));
    }
  }

  public static void checkMaxLength(String paramName, String value, int maxLength) {
    if (Objects.nonNull(value) && value.length() > maxLength) {
      throw new InputValidationException(
        String.format(INVALID_FIELD_FORMAT, paramName),
        String.format(MUST_BE_SHORTER_THAN_N_CHARACTERS, paramName, maxLength));
    }
  }

  private static boolean isDateValid(String date) {
    try {
      DATE_PATTERN.parse(date);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  public static void checkDatesOrder(String beginCoverage, String endCoverage) {
    LocalDate start = parseDate(beginCoverage);
    LocalDate end = parseDate(endCoverage);

    if(start.isAfter(end)) {
      throw new InputValidationException(INVALID_DATES_ORDER, "");
    }
  }

  private static LocalDate parseDate(String date) {
    return LocalDate.parse(date, DATE_PATTERN);
  }
}
