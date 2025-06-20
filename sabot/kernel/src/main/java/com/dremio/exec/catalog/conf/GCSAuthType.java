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
package com.dremio.exec.catalog.conf;

import io.protostuff.Tag;

public enum GCSAuthType {
  @Tag(1)
  @DisplayMetadata(label = "Service Account Keys")
  SERVICE_ACCOUNT_KEYS,

  @Tag(2)
  @DisplayMetadata(label = "Automatic/Service Account")
  AUTO,

  @Tag(3)
  @DoNotDisplay
  OAUTH2_TOKEN
}
