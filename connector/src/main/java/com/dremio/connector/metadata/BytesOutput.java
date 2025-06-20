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
package com.dremio.connector.metadata;

import java.io.IOException;
import java.io.OutputStream;

/** Generic output of bytes. */
public interface BytesOutput {
  BytesOutput NONE = os -> {};

  /**
   * Write this object to the given output stream.
   *
   * @param os output stream
   * @throws IOException if there are errors while writing
   */
  void writeTo(OutputStream os) throws IOException;

  default byte[] toByteArray() {
    try {
      java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
      writeTo(baos);
      return baos.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
