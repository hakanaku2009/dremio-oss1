// DO NOT EDIT: generated by Dremio openapi-generator.
package com.dremio.test.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.List;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * Post response body.
*/
@Value.Immutable
@Value.Style(builderVisibility = Value.Style.BuilderVisibility.PUBLIC)
@JsonDeserialize(as = ImmutablePostBody.class)
@JsonSerialize(as = ImmutablePostBody.class)
public interface PostBody {
  /**
   * Float list.
  */
  @Nullable List<Float> getFloatList();

  default ImmutablePostBody.Builder toBuilder() {
    return ImmutablePostBody.builder().from(this);
  }
}
