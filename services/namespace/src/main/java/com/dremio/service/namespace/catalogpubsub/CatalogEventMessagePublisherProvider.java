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
package com.dremio.service.namespace.catalogpubsub;

import com.dremio.service.namespace.CatalogEventProto;
import com.dremio.services.pubsub.MessagePublisher;
import com.dremio.services.pubsub.inprocess.InProcessPubSubClient;

/**
 * Provides a MessagePublisher of CatalogEventMessage. This exists because the {@link
 * InProcessPubSubClient} only permits a single publisher per topic, so it must be reused.
 */
public interface CatalogEventMessagePublisherProvider {
  CatalogEventMessagePublisherProvider NO_OP =
      new CatalogEventMessagePublisherProvider() {
        @Override
        public MessagePublisher<CatalogEventProto.CatalogEventMessage> get() {
          return (MessagePublisher<CatalogEventProto.CatalogEventMessage>) MessagePublisher.NO_OP;
        }
      };

  MessagePublisher<CatalogEventProto.CatalogEventMessage> get();
}
