/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.common.util;

import static org.apache.arrow.vector.types.Types.getMinorTypeForArrowType;

import com.dremio.common.expression.CompleteType;
import com.dremio.common.types.TypeProtos;
import com.dremio.common.types.TypeProtos.DataMode;
import com.dremio.common.types.TypeProtos.MajorType;
import com.dremio.common.types.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.Types.MinorType;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.ArrowType.ArrowTypeID;
import org.apache.arrow.vector.types.pojo.ArrowType.Decimal;
import org.apache.arrow.vector.types.pojo.ArrowType.Timestamp;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

public final class MajorTypeHelper {

  public static MinorType getArrowMinorType(TypeProtos.MinorType minorType) {
    switch (minorType) {
      case LATE:
        return MinorType.NULL;
      case TIMESTAMPMILLI:
        return MinorType.TIMESTAMPMILLI;
      case TIME:
        return MinorType.TIMEMILLI;
      case DATE:
        return MinorType.DATEMILLI;
      default:
        return MinorType.valueOf(minorType.name());
    }
  }

  public static MajorType getMajorTypeForField(Field field) {
    if (field.getType() instanceof ObjectType) {
      return Types.required(TypeProtos.MinorType.GENERIC_OBJECT);
    }
    return getMajorTypeForArrowType(field.getType(), field.isNullable(), field.getChildren());
  }

  public static MajorType getMajorTypeForArrowType(
      ArrowType arrowType, boolean isNullable, List<Field> children) {
    MajorType.Builder builder =
        MajorType.newBuilder()
            .setMinorType(getMinorTypeFromArrowMinorType(getMinorTypeForArrowType(arrowType)))
            .setMode(isNullable ? DataMode.OPTIONAL : DataMode.REQUIRED);
    ArrowTypeID fieldType = arrowType.getTypeID();
    switch (fieldType) {
      case Decimal:
        builder
            .setPrecision(((Decimal) arrowType).getPrecision())
            .setScale(((Decimal) arrowType).getScale());
        break;

      case Utf8:
      case Binary:
        builder.setPrecision(CompleteType.DEFAULT_VARCHAR_PRECISION);
        break;

      case Timestamp:
        builder.setPrecision(
            MajorTypeHelper.getPrecisionFromTimeUnit(((ArrowType.Timestamp) arrowType).getUnit()));
        break;

      case Time:
        builder.setPrecision(
            MajorTypeHelper.getPrecisionFromTimeUnit(((ArrowType.Time) arrowType).getUnit()));
        break;

      case Union:
        for (Field child : children) {
          builder.addSubType(
              getMinorTypeFromArrowMinorType(getMinorTypeForArrowType(child.getType())));
        }
        break;

      default:
        // Nothing
    }
    return builder.build();
  }

  public static ArrowType getArrowTypeForMajorType(MajorType majorType) {
    if (majorType.getMinorType() == TypeProtos.MinorType.DECIMAL) {
      return new Decimal(majorType.getPrecision(), majorType.getScale(), 128);
    }
    return getArrowMinorType(majorType.getMinorType()).getType();
  }

  public static Field getFieldForNameAndMajorType(String name, MajorType majorType) {
    try {
      return new Field(
          name,
          new FieldType(
              majorType.getMode() == DataMode.OPTIONAL, getArrowTypeForMajorType(majorType), null),
          getChildrenForMajorType(majorType));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          String.format("Could not get field for name: %s, majorType:%s", name, majorType), e);
    }
  }

  public static List<Field> getChildrenForMajorType(MajorType majorType) {
    List<Field> children = new ArrayList<>();
    switch (majorType.getMinorType()) {
      case UNION:
        for (TypeProtos.MinorType minorType : majorType.getSubTypeList()) {
          children.add(Field.nullable("", getArrowMinorType(minorType).getType()));
        }
        return children;
      default:
        return Collections.emptyList();
    }
  }

  public static List<MinorType> getArrowSubtypes(List<TypeProtos.MinorType> subTypes) {
    if (subTypes == null) {
      return null;
    }
    List<MinorType> arrowMinorTypes = new ArrayList<>();
    for (TypeProtos.MinorType minorType : subTypes) {
      arrowMinorTypes.add(getArrowMinorType(minorType));
    }
    return arrowMinorTypes;
  }

  /**
   * Return Dremio minor type for the provided Arrow minor type
   *
   * @param arrowMinorType
   * @return
   */
  public static TypeProtos.MinorType getMinorTypeFromArrowMinorType(MinorType arrowMinorType) {
    switch (arrowMinorType) {
      case TIMESTAMPMILLI:
        return TypeProtos.MinorType.TIMESTAMPMILLI;
      case TIMESTAMPMILLITZ:
        return TypeProtos.MinorType.TIMESTAMPTZ;
      case TIMEMILLI:
        return TypeProtos.MinorType.TIME;
      case DATEMILLI:
        return TypeProtos.MinorType.DATE;
      default:
        return TypeProtos.MinorType.valueOf(arrowMinorType.name());
    }
  }

  /**
   * Return decimal precision required to represent values in the given TimeUnit. Note that Dremio
   * currently only supports milliseconds for Date/Time types.
   *
   * @param unit
   * @return The decimal precision required to represent values in the given TimeUnit.
   */
  public static Integer getPrecisionFromTimeUnit(TimeUnit unit) {
    switch (unit) {
      case SECOND:
        return 0;
      case MILLISECOND:
        return 3;
      case MICROSECOND:
        return 6;
      case NANOSECOND:
        return 9;
      default:
        throw new AssertionError("Arrow TimeUnit " + unit + "not supported");
    }
  }

  private MajorTypeHelper() {
    // Utility class
  }
}
