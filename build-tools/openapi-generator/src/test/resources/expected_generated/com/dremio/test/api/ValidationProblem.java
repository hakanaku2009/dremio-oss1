// DO NOT EDIT: generated by Dremio openapi-generator.
package com.dremio.test.api;

import com.dremio.test.api.ImmutableProblem.Builder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.List;
import org.immutables.value.Value;

@Value.Immutable
@JsonDeserialize(as = ImmutableValidationProblem.class)
@JsonSerialize(as = ImmutableValidationProblem.class)
public interface ValidationProblem extends Problem {
  /**
   * An extension containing multiple validation or request errors
  */
  List<Error> getErrors();

  @Override
  Builder toBuilder();
}
