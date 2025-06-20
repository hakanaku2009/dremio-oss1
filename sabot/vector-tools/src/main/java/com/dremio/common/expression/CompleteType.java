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
package com.dremio.common.expression;

import static org.apache.arrow.vector.complex.BaseRepeatedValueVector.DATA_VECTOR_NAME;

import com.dremio.common.types.SupportsTypeCoercionsAndUpPromotions;
import com.dremio.common.types.TypeProtos;
import com.dremio.common.types.TypeProtos.MinorType;
import com.dremio.common.util.MajorTypeHelper;
import com.dremio.common.util.ObjectType;
import com.dremio.exec.exception.NoSupportedUpPromotionOrCoercionException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.flatbuffers.FlatBufferBuilder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.arrow.flatbuf.Schema;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.IntervalDayVector;
import org.apache.arrow.vector.IntervalYearVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.ZeroVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.complex.UnionVector;
import org.apache.arrow.vector.holders.BigIntHolder;
import org.apache.arrow.vector.holders.BitHolder;
import org.apache.arrow.vector.holders.ComplexHolder;
import org.apache.arrow.vector.holders.DateMilliHolder;
import org.apache.arrow.vector.holders.DecimalHolder;
import org.apache.arrow.vector.holders.Float4Holder;
import org.apache.arrow.vector.holders.Float8Holder;
import org.apache.arrow.vector.holders.IntHolder;
import org.apache.arrow.vector.holders.IntervalDayHolder;
import org.apache.arrow.vector.holders.IntervalYearHolder;
import org.apache.arrow.vector.holders.NullableBigIntHolder;
import org.apache.arrow.vector.holders.NullableBitHolder;
import org.apache.arrow.vector.holders.NullableDateMilliHolder;
import org.apache.arrow.vector.holders.NullableDecimalHolder;
import org.apache.arrow.vector.holders.NullableFixedSizeBinaryHolder;
import org.apache.arrow.vector.holders.NullableFloat4Holder;
import org.apache.arrow.vector.holders.NullableFloat8Holder;
import org.apache.arrow.vector.holders.NullableIntHolder;
import org.apache.arrow.vector.holders.NullableIntervalDayHolder;
import org.apache.arrow.vector.holders.NullableIntervalYearHolder;
import org.apache.arrow.vector.holders.NullableTimeMilliHolder;
import org.apache.arrow.vector.holders.NullableTimeStampMilliHolder;
import org.apache.arrow.vector.holders.NullableVarBinaryHolder;
import org.apache.arrow.vector.holders.NullableVarCharHolder;
import org.apache.arrow.vector.holders.ObjectHolder;
import org.apache.arrow.vector.holders.TimeMilliHolder;
import org.apache.arrow.vector.holders.TimeStampMilliHolder;
import org.apache.arrow.vector.holders.UnionHolder;
import org.apache.arrow.vector.holders.ValueHolder;
import org.apache.arrow.vector.holders.VarBinaryHolder;
import org.apache.arrow.vector.holders.VarCharHolder;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.IntervalUnit;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.UnionMode;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.ArrowType.ArrowTypeID;
import org.apache.arrow.vector.types.pojo.ArrowType.ArrowTypeVisitor;
import org.apache.arrow.vector.types.pojo.ArrowType.Binary;
import org.apache.arrow.vector.types.pojo.ArrowType.BinaryView;
import org.apache.arrow.vector.types.pojo.ArrowType.Bool;
import org.apache.arrow.vector.types.pojo.ArrowType.Date;
import org.apache.arrow.vector.types.pojo.ArrowType.Decimal;
import org.apache.arrow.vector.types.pojo.ArrowType.FixedSizeList;
import org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint;
import org.apache.arrow.vector.types.pojo.ArrowType.Int;
import org.apache.arrow.vector.types.pojo.ArrowType.Interval;
import org.apache.arrow.vector.types.pojo.ArrowType.Null;
import org.apache.arrow.vector.types.pojo.ArrowType.Struct;
import org.apache.arrow.vector.types.pojo.ArrowType.Time;
import org.apache.arrow.vector.types.pojo.ArrowType.Timestamp;
import org.apache.arrow.vector.types.pojo.ArrowType.Union;
import org.apache.arrow.vector.types.pojo.ArrowType.Utf8;
import org.apache.arrow.vector.types.pojo.ArrowType.Utf8View;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

/**
 * Describes the complete type of an arrow Field but without any name. This is used for resolving
 * types in expressions and code generation.
 */
@JsonSerialize(using = CompleteType.Ser.class)
@JsonDeserialize(using = CompleteType.De.class)
public class CompleteType {
  public static final int MAX_DECIMAL_PRECISION = 38;
  public static final int DEFAULT_VARCHAR_PRECISION = 65536;

  public static final CompleteType NULL = new CompleteType(ArrowType.Null.INSTANCE);
  public static final CompleteType LATE = new CompleteType(ArrowLateType.INSTANCE);
  public static final CompleteType OBJECT = new CompleteType(ObjectType.INTERNAL_OBJECT_TYPE);
  public static final CompleteType VARBINARY = new CompleteType(ArrowType.Binary.INSTANCE);
  public static final CompleteType BIT = new CompleteType(ArrowType.Bool.INSTANCE);
  public static final CompleteType DATE =
      new CompleteType(new ArrowType.Date(DateUnit.MILLISECOND));
  public static final CompleteType FLOAT =
      new CompleteType(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE));
  public static final CompleteType DOUBLE =
      new CompleteType(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE));
  public static final CompleteType INTERVAL_DAY_SECONDS =
      new CompleteType(new ArrowType.Interval(IntervalUnit.DAY_TIME));
  public static final CompleteType INTERVAL_YEAR_MONTHS =
      new CompleteType(new ArrowType.Interval(IntervalUnit.YEAR_MONTH));
  public static final CompleteType INT = new CompleteType(new ArrowType.Int(32, true));
  public static final CompleteType BIGINT = new CompleteType(new ArrowType.Int(64, true));
  public static final CompleteType TIME =
      new CompleteType(new ArrowType.Time(TimeUnit.MILLISECOND, 32));
  public static final CompleteType TIMESTAMP =
      new CompleteType(new ArrowType.Timestamp(TimeUnit.MILLISECOND, null));
  public static final CompleteType VARCHAR = new CompleteType(ArrowType.Utf8.INSTANCE);
  public static final CompleteType LIST = new CompleteType(ArrowType.List.INSTANCE);
  public static final CompleteType STRUCT = new CompleteType(ArrowType.Struct.INSTANCE);
  public static final CompleteType MAP = new CompleteType(new ArrowType.Map(false));
  public static final CompleteType FIXEDSIZEBINARY =
      new CompleteType(new ArrowType.FixedSizeBinary(128));
  public static final CompleteType DECIMAL =
      new CompleteType(new ArrowType.Decimal(MAX_DECIMAL_PRECISION, MAX_DECIMAL_PRECISION, 128));

  private static final String LIST_DATA_NAME = ListVector.DATA_VECTOR_NAME;
  public static final boolean REJECT_MIXED_DECIMALS = false;
  private final ArrowType type;
  private final ImmutableList<Field> children;

  public CompleteType(ArrowType type, List<Field> children) {
    super();
    this.type = type;
    this.children = ImmutableList.copyOf(children);
  }

  public CompleteType(ArrowType type, Field... children) {
    this(type, Arrays.asList(children));
  }

  public static CompleteType fromMajorType(TypeProtos.MajorType type) {
    switch (type.getMinorType()) {
      case DECIMAL:
        return CompleteType.fromDecimalPrecisionScale(type.getPrecision(), type.getScale());
      default:
        return fromMinorType(type.getMinorType());
    }
  }

  public Field toField(String name) {
    return new Field(name, new FieldType(true, type, null), children);
  }

  public Field toField(String name, boolean isNullable) {
    return new Field(name, new FieldType(isNullable, type, null), children);
  }

  // TODO: remove this function once we have nullability information in CompleteType
  public Field toField(ProvidesUnescapedPath ref, boolean isNullable) {
    return new Field(ref.getAsUnescapedPath(), new FieldType(isNullable, type, null), children);
  }

  public Field toField(ProvidesUnescapedPath ref) {
    return toField(ref, true);
  }

  private Field toInternalList() {
    return toField(LIST_DATA_NAME);
  }

  private Field toInternalField() {
    final String name = Describer.describeInternal(type);
    return toField(name);
  }

  public MinorType toMinorType() {
    if (type instanceof ObjectType) {
      return MinorType.GENERIC_OBJECT;
    }
    return MajorTypeHelper.getMinorTypeFromArrowMinorType(Types.getMinorTypeForArrowType(type));
  }

  public ArrowType getType() {
    return type;
  }

  public <T extends ArrowType> T getType(Class<T> clazz) {
    Preconditions.checkArgument(
        clazz.isAssignableFrom(type.getClass()),
        "Trying to unwrap type of %s when current type is %s.",
        clazz.getName(),
        Describer.describe(type));
    return clazz.cast(type);
  }

  public ImmutableList<Field> getChildren() {
    return children;
  }

  public Field getOnlyChild() {
    Preconditions.checkArgument(children.size() == 1);
    return children.get(0);
  }

  public CompleteType getOnlyChildType() {
    return CompleteType.fromField(getOnlyChild());
  }

  public boolean isComparable() {
    switch (type.getTypeID()) {
      case Struct:
      case Map:
        return false;
      default:
        return true;
    }
  }

  public static CompleteType fromField(Field field) {
    // IGNORE this until the NullableMapVector.getField() returns a nullable type.
    //    Preconditions.checkArgument(field.isNullable(), "Dremio only supports nullable types.");
    return new CompleteType(field.getType(), field.getChildren());
  }

  public static CompleteType fromDecimalPrecisionScale(int precision, int scale) {
    // TODO: lots of ARP failures with this check.
    // Preconditions.checkArgument(scale >= 0, "invalid scale " + scale +
    //  "must be >= 0");
    // Preconditions.checkArgument(precision > 0 && precision >= scale,
    //  "invalid precision " + precision + ", must be > 0 and >= scale " + scale);
    return new CompleteType(new ArrowType.Decimal(precision, scale, 128));
  }

  public static CompleteType fromMinorType(MinorType type) {
    switch (type) {

      // simple types.
      case BIGINT:
        return BIGINT;
      case BIT:
        return BIT;
      case DATE:
        return DATE;
      case FLOAT4:
        return FLOAT;
      case FLOAT8:
        return DOUBLE;
      case INT:
        return INT;
      case INTERVALDAY:
        return INTERVAL_DAY_SECONDS;
      case INTERVALYEAR:
        return INTERVAL_YEAR_MONTHS;
      case TIME:
        return TIME;
      case TIMESTAMPMILLI:
        return TIMESTAMP;
      case VARBINARY:
        return VARBINARY;
      case VARCHAR:
        return VARCHAR;
      case GENERIC_OBJECT:
        return OBJECT;

      case LATE:
        return LATE;

      case DECIMAL:
        return DECIMAL;
      // types that need additional information
      case LIST:
        return LIST;
      case STRUCT:
        return STRUCT;
      case MAP:
        return MAP;
      case UNION:
        throw new UnsupportedOperationException(
            "You can't create a complete type from a minor type when working with type of "
                + type.name());

      // unsupported types.
      case INTERVAL:
      case MONEY:
      case NULL:
      case SMALLINT:
      case TIMESTAMPTZ:
      case TIMETZ:
      case TINYINT:
      case UINT1:
      case UINT2:
      case UINT4:
      case UINT8:
      case VAR16CHAR:
      case FIXED16CHAR:
      case FIXEDSIZEBINARY:
      case FIXEDCHAR:
      case DECIMAL9:
      case DECIMAL18:
      case DECIMAL28DENSE:
      case DECIMAL28SPARSE:
      case DECIMAL38DENSE:
      case DECIMAL38SPARSE:
      default:
        throw new UnsupportedOperationException("unsupported type " + type.name());
    }
  }

  public boolean isText() {
    return type.getTypeID() == ArrowTypeID.Utf8;
  }

  public boolean isInt() {
    return equals(INT);
  }

  public boolean isBigInt() {
    return equals(BIGINT);
  }

  public boolean isFloat() {
    return equals(FLOAT);
  }

  public boolean isDouble() {
    return equals(DOUBLE);
  }

  public boolean isNumeric() {
    switch (type.getTypeID()) {
      case Decimal:
      case FloatingPoint:
      case Int:
        return true;
      default:
        return false;
    }
  }

  public boolean isBoolean() {
    return type.getTypeID() == ArrowTypeID.Bool;
  }

  public boolean isTemporal() {
    switch (type.getTypeID()) {
      case Date:
      case Time:
      case Timestamp:
        return true;
      default:
        return false;
    }
  }

  public boolean isNull() {
    return type == ArrowType.Null.INSTANCE;
  }

  public boolean isUnion() {
    return type.getTypeID() == ArrowTypeID.Union;
  }

  public boolean isStruct() {
    return type.getTypeID() == ArrowTypeID.Struct;
  }

  public boolean isMap() {
    return type.getTypeID() == ArrowTypeID.Map;
  }

  public boolean isList() {
    return type.getTypeID() == ArrowTypeID.List;
  }

  public boolean isLate() {
    return this == CompleteType.LATE;
  }

  public boolean isComplex() {
    return isStruct() || isList() || isMap();
  }

  public boolean isScalar() {
    switch (type.getTypeID()) {
      case List:
      case Struct:
      case Map:
      case Union:
        return false;
      default:
        return true;
    }
  }

  public boolean isFixedWidthScalar() {
    switch (type.getTypeID()) {
      case List:
      case Struct:
      case Map:
      case Union:
      case Binary:
      case Utf8:
        return false;
      default:
        return true;
    }
  }

  public boolean isFixedWidthType() {
    if (type.isComplex()) {
      return false;
    }
    if (type.getTypeID() == ArrowTypeID.Utf8
        || type.getTypeID() == ArrowTypeID.Binary
        || type.getTypeID() == ArrowTypeID.LargeBinary
        || type.getTypeID() == ArrowTypeID.LargeUtf8) {
      return false;
    }
    return true;
  }

  public boolean isVariableWidthScalar() {
    switch (type.getTypeID()) {
      case Utf8:
      case Binary:
        return true;
      default:
        return false;
    }
  }

  public boolean isDecimal() {
    return type.getTypeID() == ArrowTypeID.Decimal;
  }

  public boolean isValidDecimal() {
    if (!isDecimal()) {
      return false;
    }
    if (getPrecision() > MAX_DECIMAL_PRECISION) {
      throw new IllegalArgumentException(
          "Illegal decimal precision in type: "
              + type
              + ". Max supported precision is "
              + MAX_DECIMAL_PRECISION);
    }
    return true;
  }

  public Class<? extends FieldVector> getValueVectorClass() {
    switch (Types.getMinorTypeForArrowType(type)) {
      case UNION:
        return UnionVector.class;
      case STRUCT:
        return StructVector.class;
      case LIST:
        return ListVector.class;
      case MAP:
        return MapVector.class;
      case NULL:
        return ZeroVector.class;
      case TINYINT:
        return TinyIntVector.class;
      case UINT1:
        return UInt1Vector.class;
      case UINT2:
        return UInt2Vector.class;
      case SMALLINT:
        return SmallIntVector.class;
      case INT:
        return IntVector.class;
      case UINT4:
        return UInt4Vector.class;
      case FLOAT4:
        return Float4Vector.class;
      case INTERVALYEAR:
        return IntervalYearVector.class;
      case TIMEMILLI:
        return TimeMilliVector.class;
      case BIGINT:
        return BigIntVector.class;
      case UINT8:
        return UInt8Vector.class;
      case FLOAT8:
        return Float8Vector.class;
      case DATEMILLI:
        return DateMilliVector.class;
      case TIMESTAMPMILLI:
        return TimeStampMilliVector.class;
      case INTERVALDAY:
        return IntervalDayVector.class;
      case DECIMAL:
        return DecimalVector.class;
      case VARBINARY:
        return VarBinaryVector.class;
      case VARCHAR:
        return VarCharVector.class;
      case BIT:
        return BitVector.class;
      default:
        break;
    }
    throw new UnsupportedOperationException(
        String.format("Unable to determine vector class for type %s.", type));
  }

  @SuppressWarnings("deprecation")
  public Class<? extends ValueHolder> getHolderClass() {

    if (this == OBJECT) {
      return org.apache.arrow.vector.holders.ObjectHolder.class;
    }

    return type.accept(
        new ArrowTypeVisitor<Class<? extends ValueHolder>>() {

          @Override
          public Class<? extends ValueHolder> visit(Null type) {
            throw new UnsupportedOperationException("You cannot create a holder for a NULL type.");
          }

          @Override
          public Class<? extends ValueHolder> visit(Struct type) {
            return ComplexHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(
              org.apache.arrow.vector.types.pojo.ArrowType.List type) {
            return ComplexHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(
              org.apache.arrow.vector.types.pojo.ArrowType.ListView type) {
            return ComplexHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(Union type) {
            return UnionHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(Int type) {
            Preconditions.checkArgument(type.getIsSigned());

            switch (type.getBitWidth()) {
              case 32:
                return NullableIntHolder.class;
              case 64:
                return NullableBigIntHolder.class;
              default:
                throw new UnsupportedOperationException(
                    "Don't support int width of " + type.getBitWidth());
            }
          }

          @Override
          public Class<? extends ValueHolder> visit(FloatingPoint type) {
            switch (type.getPrecision()) {
              case DOUBLE:
                return NullableFloat8Holder.class;
              case SINGLE:
                return NullableFloat4Holder.class;
              default:
                throw new UnsupportedOperationException(
                    "Don't support float with precision of " + type.getPrecision());
            }
          }

          @Override
          public Class<? extends ValueHolder> visit(Utf8 type) {
            return NullableVarCharHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(Utf8View type) {
            throw new UnsupportedOperationException("Utf8View is not supported.");
          }

          @Override
          public Class<? extends ValueHolder> visit(Binary type) {
            return NullableVarBinaryHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(BinaryView type) {
            throw new UnsupportedOperationException("BinaryView is not supported.");
          }

          @Override
          public Class<? extends ValueHolder> visit(Bool type) {
            return NullableBitHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(Decimal type) {
            return NullableDecimalHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(Date type) {
            return NullableDateMilliHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(Time type) {
            return NullableTimeMilliHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(Timestamp type) {
            return NullableTimeStampMilliHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(Interval type) {
            switch (type.getUnit()) {
              case DAY_TIME:
                return NullableIntervalDayHolder.class;
              case YEAR_MONTH:
                return NullableIntervalYearHolder.class;
              default:
                throw new UnsupportedOperationException(
                    "Don't support interval with unit of " + type.getUnit());
            }
          }

          @Override
          public Class<? extends ValueHolder> visit(FixedSizeList type) {
            return ComplexHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(ArrowType.FixedSizeBinary type) {
            return ComplexHolder.class;
          }

          @Override
          public Class<? extends ValueHolder> visit(ArrowType.LargeBinary type) {
            throw new UnsupportedOperationException("Dremio does not support LargeBinary yet.");
          }

          @Override
          public Class<? extends ValueHolder> visit(ArrowType.LargeList type) {
            throw new UnsupportedOperationException("Dremio does not support LargeUtf8 yet.");
          }

          @Override
          public Class<? extends ValueHolder> visit(ArrowType.LargeUtf8 type) {
            throw new UnsupportedOperationException("Dremio does not support LargeUtf8 yet.");
          }

          @Override
          public Class<? extends ValueHolder> visit(ArrowType.Duration type) {
            throw new UnsupportedOperationException("Dremio does not support duration yet.");
          }

          @Override
          public Class<? extends ValueHolder> visit(ArrowType.RunEndEncoded type) {
            throw new UnsupportedOperationException("Dremio does not support RunEndEncoded yet.");
          }

          @Override
          public Class<? extends ValueHolder> visit(ArrowType.LargeListView type) {
            throw new UnsupportedOperationException("Dremio does not support LargeListView yet.");
          }

          @Override
          public Class<? extends ValueHolder> visit(ArrowType.Map type) {
            return ComplexHolder.class;
          }
        });
  }

  public static <T extends ValueHolder> CompleteType fromHolderClass(Class<T> holderClass) {
    if (holderClass.equals(IntHolder.class)) {
      return CompleteType.INT;
    } else if (holderClass.equals(NullableIntHolder.class)) {
      return CompleteType.INT;
    } else if (holderClass.equals(Float4Holder.class)) {
      return CompleteType.FLOAT;
    } else if (holderClass.equals(NullableFloat4Holder.class)) {
      return CompleteType.FLOAT;
    } else if (holderClass.equals(IntervalYearHolder.class)) {
      return CompleteType.INTERVAL_YEAR_MONTHS;
    } else if (holderClass.equals(NullableIntervalYearHolder.class)) {
      return CompleteType.INTERVAL_YEAR_MONTHS;
    } else if (holderClass.equals(TimeMilliHolder.class)) {
      return CompleteType.TIME;
    } else if (holderClass.equals(NullableTimeMilliHolder.class)) {
      return CompleteType.TIME;
    } else if (holderClass.equals(BigIntHolder.class)) {
      return CompleteType.BIGINT;
    } else if (holderClass.equals(NullableBigIntHolder.class)) {
      return CompleteType.BIGINT;
    } else if (holderClass.equals(Float8Holder.class)) {
      return CompleteType.DOUBLE;
    } else if (holderClass.equals(NullableFloat8Holder.class)) {
      return CompleteType.DOUBLE;
    } else if (holderClass.equals(DateMilliHolder.class)) {
      return CompleteType.DATE;
    } else if (holderClass.equals(NullableDateMilliHolder.class)) {
      return CompleteType.DATE;
    } else if (holderClass.equals(TimeStampMilliHolder.class)) {
      return CompleteType.TIMESTAMP;
    } else if (holderClass.equals(NullableTimeStampMilliHolder.class)) {
      return CompleteType.TIMESTAMP;
    } else if (holderClass.equals(IntervalDayHolder.class)) {
      return CompleteType.INTERVAL_DAY_SECONDS;
    } else if (holderClass.equals(NullableIntervalDayHolder.class)) {
      return CompleteType.INTERVAL_DAY_SECONDS;
    } else if (holderClass.equals(DecimalHolder.class)) {
      return CompleteType.fromDecimalPrecisionScale(0, 0);
    } else if (holderClass.equals(NullableDecimalHolder.class)) {
      return CompleteType.fromDecimalPrecisionScale(0, 0);
    } else if (holderClass.equals(VarBinaryHolder.class)) {
      return CompleteType.VARBINARY;
    } else if (holderClass.equals(NullableVarBinaryHolder.class)) {
      return CompleteType.VARBINARY;
    } else if (holderClass.equals(VarCharHolder.class)) {
      return CompleteType.VARCHAR;
    } else if (holderClass.equals(NullableVarCharHolder.class)) {
      return CompleteType.VARCHAR;
    } else if (holderClass.equals(BitHolder.class)) {
      return CompleteType.BIT;
    } else if (holderClass.equals(NullableBitHolder.class)) {
      return CompleteType.BIT;
    } else if (holderClass.equals(ObjectHolder.class)) {
      return CompleteType.OBJECT;
    } else if (holderClass.equals(UnionHolder.class)) {
      return new CompleteType(new Union(UnionMode.Sparse, new int[0]));
    } else if (holderClass.equals(NullableFixedSizeBinaryHolder.class)) {
      return CompleteType.FIXEDSIZEBINARY;
    }

    throw new UnsupportedOperationException(
        String.format(
            "%s is not supported for 'getValueHolderType' method.", holderClass.getName()));
  }

  public static List<Field> mergeFieldLists(List<Field> fields1, List<Field> fields2) {
    Map<String, Field> secondFieldMap = new LinkedHashMap<>();
    List<Field> mergedList = new ArrayList<>();
    for (Field field : fields2) {
      secondFieldMap.put(field.getName().toLowerCase(), field);
    }
    for (Field field : fields1) {
      Field matchingField = secondFieldMap.remove(field.getName().toLowerCase());
      if (matchingField != null) {
        mergedList.add(
            fromField(field)
                .merge(fromField(matchingField))
                .toField(field.getName(), field.isNullable()));
      } else {
        mergedList.add(field);
      }
    }
    for (Field field : secondFieldMap.values()) {
      mergedList.add(field);
    }
    return mergedList;
  }

  public static List<Field> mergeFieldListsWithUpPromotionOrCoercion(
      List<Field> tableFields,
      List<Field> fileFields,
      SupportsTypeCoercionsAndUpPromotions coercionRulesSet) {
    Map<String, Field> secondFieldMap = new LinkedHashMap<>();
    List<Field> mergedList = new ArrayList<>();
    for (Field field : fileFields) {
      secondFieldMap.put(field.getName().toLowerCase(), field);
    }

    for (Field tableSchemaField : tableFields) {
      Field matchingField = secondFieldMap.remove(tableSchemaField.getName().toLowerCase());
      if (matchingField != null) {
        try {
          mergedList.add(
              fromField(tableSchemaField)
                  .mergeFieldListsWithUpPromotionOrCoercion(
                      fromField(matchingField), coercionRulesSet)
                  .toField(tableSchemaField.getName(), tableSchemaField.isNullable()));
        } catch (NoSupportedUpPromotionOrCoercionException e) {
          e.addColumnName(tableSchemaField.getName());
          throw e;
        }
      } else {
        mergedList.add(tableSchemaField);
      }
    }
    mergedList.addAll(secondFieldMap.values());
    return mergedList;
  }

  public CompleteType merge(CompleteType type2) {
    return merge(type2, REJECT_MIXED_DECIMALS);
  }

  public CompleteType merge(CompleteType type2, boolean allowMixedDecimals) {
    CompleteType type1 = this;

    // both fields are unions.
    if (type1.getType().getTypeID() == ArrowTypeID.Union
        && type2.getType().getTypeID() == ArrowTypeID.Union) {
      List<Field> subTypes = mergeFieldLists(type1.getChildren(), type2.getChildren());
      int[] typeIds = getTypeIds(subTypes);
      return new CompleteType(new Union(UnionMode.Sparse, typeIds), subTypes);
    }

    if (type1.getType().equals(type2.getType())) {

      if (type1.isScalar()) {
        // both are scalars.
        return type1;

      } else if (type1.isList()) {
        // both are lists
        CompleteType child1 = fromField(type1.getOnlyChild());
        CompleteType child2 = fromField(type2.getOnlyChild());
        return new CompleteType(type1.getType(), child1.merge(child2).toInternalList());
      } else if (type1.isStruct()) {
        // both are structs.
        return new CompleteType(
            type1.getType(), mergeFieldLists(type1.getChildren(), type2.getChildren()));
      } else if (type1.isMap()) {
        return new CompleteType(
            type1.getType(),
            struct(
                    mergeFieldLists(
                        type1.getOnlyChild().getChildren(), type2.getOnlyChild().getChildren()))
                .toField(MapVector.DATA_VECTOR_NAME, false));
      }
    }

    if (type1.getType().equals(Null.INSTANCE)) {
      return type2;
    }

    if (type2.getType().equals(Null.INSTANCE)) {
      return type1;
    }

    if (type1.getType().getTypeID() == ArrowTypeID.Decimal
        || type2.getType().getTypeID() == ArrowTypeID.Decimal) {
      // Currently decimal is allowed to be mixed with other types only as differing type in
      // then-else block of if.
      // All other cases are rejected.
      // a. Mixed decimals in scan b. Decimal with any other type in all cases including then-else.
      if (!allowMixedDecimals) {
        throw new UnsupportedOperationException(
            "Cannot have mixed types for a decimal field. "
                + "Found "
                + "types"
                + " : "
                + type1.getType()
                + " , "
                + type2.getType());
      } else {
        return coerceDecimalTypes(type1, type2);
      }
    }

    final List<Field> fields1 =
        type1.isUnion() ? type1.getChildren() : Collections.singletonList(type1.toInternalField());
    final List<Field> fields2 =
        type2.isUnion() ? type2.getChildren() : Collections.singletonList(type2.toInternalField());

    List<Field> mergedFields = mergeFieldLists(fields1, fields2);
    int[] typeIds = getTypeIds(mergedFields);
    return new CompleteType(new Union(UnionMode.Sparse, typeIds), mergedFields);
  }

  /**
   * Merges a file {@code CompleteType} with the current table {@code CompleteType} by following a
   * set of schema up-promotion and type coercion rules. This method should be used instead of
   * {@link #merge(CompleteType)} when Union types are not desirable.
   *
   * @param fileType the {@code CompleteType} of the file
   * @return the merged {@code CompleteType} after up promotion
   * @throws UnsupportedOperationException if the merge could not be done due to incompatible types
   */
  @VisibleForTesting
  CompleteType mergeFieldListsWithUpPromotionOrCoercion(
      CompleteType fileType, SupportsTypeCoercionsAndUpPromotions rules)
      throws UnsupportedOperationException {
    CompleteType tableType = this;

    if (tableType.isUnion()) {
      tableType = removeUnions(tableType, rules);
    }

    if (fileType.isUnion()) {
      fileType = removeUnions(fileType, rules);
    }

    if (tableType.getType().equals(fileType.getType())) {
      if (tableType.isScalar()) {
        return tableType;
      }

      if (tableType.isList()) {
        CompleteType tableTypeChild = fromField(tableType.getOnlyChild());
        CompleteType fileTypeChild = fromField(fileType.getOnlyChild());
        return new CompleteType(
            tableType.getType(),
            tableTypeChild
                .mergeFieldListsWithUpPromotionOrCoercion(fileTypeChild, rules)
                .toInternalList());
      }

      if (tableType.isStruct()) {
        return new CompleteType(
            tableType.getType(),
            mergeFieldListsWithUpPromotionOrCoercion(
                tableType.getChildren(), fileType.getChildren(), rules));
      }

      if (tableType.isMap()) {
        CompleteType entryStruct =
            new CompleteType(
                tableType.getOnlyChildType().getType(),
                mergeFieldListsWithUpPromotionOrCoercion(
                    tableType.getOnlyChild().getChildren(),
                    fileType.getOnlyChild().getChildren(),
                    rules));
        return new CompleteType(
            tableType.getType(), entryStruct.toField(MapVector.DATA_VECTOR_NAME, false));
      }

      throw new IllegalStateException("Unsupported type: " + tableType);
    }

    Optional<CompleteType> tableSchemaUpPromotion =
        rules.getUpPromotionRules().getResultantType(fileType, tableType);
    if (tableSchemaUpPromotion.isPresent()) {
      return tableSchemaUpPromotion.get();
    }

    Optional<CompleteType> typeCoercion =
        rules.getTypeCoercionRules().getResultantType(fileType, tableType);
    if (typeCoercion.isPresent()) {
      return typeCoercion.get();
    }
    throw new NoSupportedUpPromotionOrCoercionException(fileType, tableType);
  }

  @VisibleForTesting
  public static CompleteType removeUnions(
      CompleteType type, SupportsTypeCoercionsAndUpPromotions rules) {
    List<Field> fieldList = type.getChildren();
    type = new CompleteType(fieldList.get(0).getType(), fieldList.get(0).getChildren());
    for (Field currentField : fieldList) {
      CompleteType currentCompleteType =
          new CompleteType(currentField.getType(), currentField.getChildren());
      type = type.mergeFieldListsWithUpPromotionOrCoercion(currentCompleteType, rules);
    }
    return type;
  }

  // TODO : Move following to Output Derivation as part of DX-16966
  private CompleteType coerceDecimalTypes(CompleteType type1, CompleteType type2) {
    if (type1.isDecimal() && type2.isDecimal()) {
      return getDecimalUnion(type1, type2);
    } else {
      CompleteType decimalType, nonDecimalType;
      if (type1.isDecimal()) {
        decimalType = type1;
        nonDecimalType = type2;
      } else {
        decimalType = type2;
        nonDecimalType = type1;
      }
      if (nonDecimalType.equals(CompleteType.BIGINT)) {
        return getDecimalUnion(decimalType, CompleteType.fromDecimalPrecisionScale(19, 0));
      } else if (nonDecimalType.equals(CompleteType.INT)) {
        return getDecimalUnion(decimalType, CompleteType.fromDecimalPrecisionScale(10, 0));
      } else {
        throw new UnsupportedOperationException(
            "Cannot have mixed types for a decimal field. "
                + "Found "
                + "types"
                + " : "
                + type1.getType()
                + " , "
                + type2.getType());
      }
    }
  }

  private CompleteType getDecimalUnion(CompleteType type1, CompleteType type2) {
    int outputScale = Math.max(type1.getScale(), type2.getScale());
    int outputPrecision =
        Math.max(type1.getPrecision() - type1.getScale(), type2.getPrecision() - type2.getScale())
            + outputScale;

    if (outputPrecision > MAX_DECIMAL_PRECISION) {
      throw new UnsupportedOperationException(
          "Incompatible precision and scale(common precision "
              + "is greater than "
              + MAX_DECIMAL_PRECISION
              + " digits. Please consider downcasting the values "
              + "Found "
              + "types"
              + " : "
              + type1.getType()
              + " , "
              + type2.getType());
    }

    return CompleteType.fromDecimalPrecisionScale(outputPrecision, outputScale);
  }

  public static int[] getTypeIds(List<Field> subTypes) {
    int[] typeIds = new int[subTypes.size()];
    for (int i = 0; i < typeIds.length; i++) {
      typeIds[i] = Types.getMinorTypeForArrowType(subTypes.get(i).getType()).ordinal();
    }
    return typeIds;
  }

  public Integer getPrecision() {
    return type.accept(
        new AbstractArrowTypeVisitor<Integer>() {

          @Override
          public Integer visit(Utf8 type) {
            return 65536;
          }

          @Override
          public Integer visit(Binary type) {
            return 65536;
          }

          @Override
          public Integer visit(Decimal type) {
            return type.getPrecision();
          }

          @Override
          public Integer visit(Time type) {
            return MajorTypeHelper.getPrecisionFromTimeUnit(type.getUnit());
          }

          @Override
          public Integer visit(Timestamp type) {
            return MajorTypeHelper.getPrecisionFromTimeUnit(type.getUnit());
          }

          @Override
          protected Integer visitGeneric(ArrowType type) {
            return null;
          }
        });
  }

  public Integer getScale() {
    return type.accept(
        new AbstractArrowTypeVisitor<Integer>() {

          @Override
          public Integer visit(Decimal type) {
            return type.getScale();
          }

          @Override
          protected Integer visitGeneric(ArrowType type) {
            return null;
          }
        });
  }

  @Override
  public String toString() {
    return Describer.describe(this);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(children, type);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    CompleteType other = (CompleteType) obj;

    return Objects.equal(children, other.children) && Objects.equal(type, other.type);
  }

  public byte[] serialize() {
    FlatBufferBuilder builder = new FlatBufferBuilder();
    builder.finish(serialize(builder));
    return builder.sizedByteArray();
  }

  public static CompleteType deserialize(byte[] bytes) {
    Schema schema = Schema.getRootAsSchema(ByteBuffer.wrap(bytes));
    org.apache.arrow.vector.types.pojo.Schema s =
        org.apache.arrow.vector.types.pojo.Schema.convertSchema(schema);
    return CompleteType.fromField(s.getFields().get(0));
  }

  public int serialize(FlatBufferBuilder builder) {
    org.apache.arrow.vector.types.pojo.Schema schema =
        new org.apache.arrow.vector.types.pojo.Schema(Collections.singletonList(this.toField("f")));
    return schema.getSchema(builder);
  }

  public static class Ser extends JsonSerializer<CompleteType> {

    @Override
    public void serialize(CompleteType value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException, JsonProcessingException {
      gen.writeBinary(value.serialize());
    }
  }

  public static class De extends JsonDeserializer<CompleteType> {

    @Override
    public CompleteType deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException, JsonProcessingException {
      return CompleteType.deserialize(p.getBinaryValue());
    }
  }

  public CompleteType asList() {
    return new CompleteType(ArrowType.List.INSTANCE, this.toField(LIST_DATA_NAME));
  }

  public static CompleteType struct(Iterable<Field> fields) {
    return new CompleteType(ArrowType.Struct.INSTANCE, ImmutableList.copyOf(fields));
  }

  public static CompleteType union(Field... fields) {
    return union(FluentIterable.from(fields));
  }

  public static CompleteType union(Iterable<Field> fields) {
    ImmutableList<Field> listOfFields = ImmutableList.copyOf(fields);
    int[] typeIds = new int[listOfFields.size()];
    for (int i = 0; i < typeIds.length; i++) {
      typeIds[i] =
          MajorTypeHelper.getArrowMinorType(
                  CompleteType.fromField(listOfFields.get(i)).toMinorType())
              .ordinal();
    }
    return new CompleteType(new ArrowType.Union(UnionMode.Sparse, typeIds), listOfFields);
  }

  public static CompleteType struct(Field... fields) {
    return new CompleteType(ArrowType.Struct.INSTANCE, fields);
  }

  /***
   * Gets SQL data type name for given Dremio RPC-/protobuf-level data type.
   * @return
   *   canonical keyword sequence for SQL data type (leading keywords in
   *   corresponding {@code <data type>}; what
   *   {@code INFORMATION_SCHEMA.COLUMNS.TYPE_NAME} would list)
   */
  public String getSqlTypeName() {
    return type.accept(new SqlTypeNameVisitor());
  }

  public int getSqlDisplaySize() {
    return type.accept(new SqlDisplaySizeVisitor());
  }

  public boolean isSigned() {
    return type.accept(
        new AbstractArrowTypeVisitor<Boolean>() {

          @Override
          public Boolean visit(Int type) {
            return true;
          }

          @Override
          public Boolean visit(FloatingPoint type) {
            return true;
          }

          @Override
          public Boolean visit(Decimal type) {
            return true;
          }

          @Override
          protected Boolean visitGeneric(ArrowType type) {
            return false;
          }
        });
  }

  public boolean isSortable() {
    return type.accept(
        new AbstractArrowTypeVisitor<Boolean>() {

          @Override
          public Boolean visit(Null type) {
            return false;
          }

          @Override
          public Boolean visit(Struct type) {
            return false;
          }

          @Override
          public Boolean visit(ArrowType.Map type) {
            return false;
          }

          @Override
          public Boolean visit(org.apache.arrow.vector.types.pojo.ArrowType.List type) {
            return false;
          }

          @Override
          public Boolean visit(org.apache.arrow.vector.types.pojo.ArrowType.ListView type) {
            return false;
          }

          @Override
          public Boolean visit(Union type) {
            return false;
          }

          @Override
          protected Boolean visitGeneric(ArrowType type) {
            return true;
          }
        });
  }

  /**
   * Convert arrow type to the arrow type supported by dremio
   *
   * @param arrowType original arrow type
   * @return the arrow type supported by dremio
   */
  private static ArrowType convertToSupportedArrowType(ArrowType arrowType) {
    switch (arrowType.getTypeID()) {
      case Int:
        ArrowType.Int arrowInt = (ArrowType.Int) arrowType;
        if (arrowInt.getBitWidth() < 32) {
          return CompleteType.INT.getType();
        } else if (arrowInt.getBitWidth() == 32 && !arrowInt.getIsSigned()) {
          return CompleteType.BIGINT.getType();
        } else if (arrowInt.getBitWidth() == 64 && !arrowInt.getIsSigned()) {
          return CompleteType.DOUBLE.getType();
        } else {
          return arrowInt;
        }
      case Date:
        // We don't support DateDay, so we should convert it to DataMilli.
        return CompleteType.DATE.getType();
      case Time:
        return CompleteType.TIME.getType();
      case Timestamp:
        // We always treat timezone as null.
        return CompleteType.TIMESTAMP.getType();
      default:
        return arrowType;
    }
  }

  /**
   * Return DATA_VECTOR_NAME as field name if fieldName is null.
   *
   * @param fieldName original field name
   * @return field name after conversion
   */
  private static String convertFieldName(String fieldName) {
    return fieldName == null ? DATA_VECTOR_NAME : fieldName;
  }

  /**
   * Convert arrow fields to dremio fields that are supported in dremio.
   *
   * @param arrowFields arrow fields
   * @return dremio fields
   */
  public static List<Field> convertToDremioFields(List<Field> arrowFields) {
    if (arrowFields == null) {
      return null;
    }
    List<Field> dremioFields = new ArrayList<>();
    for (Field field : arrowFields) {
      if (field.getType().getTypeID() == ArrowTypeID.Map) {
        dremioFields.add(convertMapToDremioField(field));
      } else {
        dremioFields.add(
            new Field(
                convertFieldName(field.getName()),
                new FieldType(true, convertToSupportedArrowType(field.getType()), null),
                convertToDremioFields(field.getChildren())));
      }
    }
    return dremioFields;
  }

  public static Field convertMapToDremioField(Field arrowField) {
    Field entryStruct = arrowField.getChildren().get(0);
    Field keyField = entryStruct.getChildren().get(0);
    Field valueField = entryStruct.getChildren().get(1);
    if (keyField.getType().isComplex()) {
      throw new UnsupportedOperationException(
          "Cannot have complex key in map data type for field " + arrowField.getName());
    }
    List<Field> childFields = new ArrayList<>();
    childFields.add(
        new Field(
            convertFieldName(keyField.getName()),
            new FieldType(false, convertToSupportedArrowType(keyField.getType()), null),
            null));
    childFields.add(convertToDremioFields(Arrays.asList(valueField)).get(0));
    Field entries =
        new Field(
            entryStruct.getName() == null ? MapVector.DATA_VECTOR_NAME : entryStruct.getName(),
            new FieldType(false, convertToSupportedArrowType(entryStruct.getType()), null),
            childFields);
    return new Field(
        convertFieldName(arrowField.getName()),
        new FieldType(
            arrowField.isNullable(), convertToSupportedArrowType(arrowField.getType()), null),
        Collections.singletonList(entries));
  }
}
