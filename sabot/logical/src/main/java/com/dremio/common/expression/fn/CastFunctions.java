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
package com.dremio.common.expression.fn;

import com.dremio.common.types.TypeProtos.MinorType;
import java.util.HashMap;
import java.util.Map;

public class CastFunctions {

  private static Map<MinorType, String> TYPE2FUNC = new HashMap<>();

  static {
    TYPE2FUNC.put(MinorType.UNION, "castUNION");
    TYPE2FUNC.put(MinorType.BIGINT, "castBIGINT");
    TYPE2FUNC.put(MinorType.INT, "castINT");
    TYPE2FUNC.put(MinorType.BIT, "castBIT");
    TYPE2FUNC.put(MinorType.TINYINT, "castTINYINT");
    TYPE2FUNC.put(MinorType.FLOAT4, "castFLOAT4");
    TYPE2FUNC.put(MinorType.FLOAT8, "castFLOAT8");
    TYPE2FUNC.put(MinorType.VARCHAR, "castVARCHAR");
    TYPE2FUNC.put(MinorType.VAR16CHAR, "castVAR16CHAR");
    TYPE2FUNC.put(MinorType.VARBINARY, "castVARBINARY");
    TYPE2FUNC.put(MinorType.DATE, "castDATE");
    TYPE2FUNC.put(MinorType.TIME, "castTIME");
    TYPE2FUNC.put(MinorType.TIMESTAMPMILLI, "castTIMESTAMP");
    TYPE2FUNC.put(MinorType.TIMESTAMPTZ, "castTIMESTAMPTZ");
    TYPE2FUNC.put(MinorType.INTERVALDAY, "castINTERVALDAY");
    TYPE2FUNC.put(MinorType.INTERVALYEAR, "castINTERVALYEAR");
    TYPE2FUNC.put(MinorType.INTERVAL, "castINTERVAL");
    TYPE2FUNC.put(MinorType.DECIMAL, "castDECIMAL");
  }

  /**
   * Given the target type, get the appropriate cast function
   *
   * @param targetMinorType the target data type
   * @return the name of cast function
   */
  public static String getCastFunc(MinorType targetMinorType) {
    String func = TYPE2FUNC.get(targetMinorType);
    if (func != null) {
      return func;
    }

    throw new RuntimeException(
        String.format("cast function for type %s is not defined", targetMinorType.name()));
  }
}
