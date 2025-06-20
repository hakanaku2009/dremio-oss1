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
package com.dremio.exec.vector.complex.fn;

public interface ExtendedTypeName {
  public static final String BINARY = "$binary"; // base64 encoded binary (ZHJpbGw=)  [from Mongo]
  public static final String TYPE = "$type"; // type of binary data
  public static final String DATE = "$dateDay"; // ISO date with no time. such as (12-24-27)
  public static final String TIME = "$time"; // ISO time with no timezone (19:20:30.45Z)
  public static final String TIMESTAMP =
      "$date"; // ISO standard time (2009-02-23T00:00:00.000-08:00) [from Mongo]
  public static final String INTERVAL = "$interval"; // ISO standard duration (PT26.4S)
  public static final String INTEGER = "$numberInt";
  public static final String LONG = "$numberLong"; // 8 byte signed integer (123) [from Mongo]
  public static final String DECIMAL = "$decimal";
  public static final String DOUBLE = "$double";
  public static final String FLOAT = "$float";
}
